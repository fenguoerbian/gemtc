package org.drugis.mtc.yadas

import org.drugis.mtc._
import gov.lanl.yadas._

import org.apache.commons.math.stat.descriptive.moment.StandardDeviation
import org.apache.commons.math.stat.descriptive.moment.Mean
import org.apache.commons.math.linear.ArrayRealVector

class EstimateImpl(val mean: Double, val sd: Double)
extends Estimate {
	def getMean = mean
	def getStandardDeviation = sd
}


abstract class Parameter
extends Estimate {
	private val mean = new Mean()
	private val sd = new StandardDeviation(false)

	def update() {
		val value = getValue
		mean.increment(value)
		sd.increment(value)
	}

	def getMean = mean.getResult
	def getStandardDeviation = sd.getResult
	def getValue: Double
}

class DirectParameter(p: MCMCParameter, i: Int)
extends Parameter {
	override def getValue = p.getValue(i)
}

class IndirectParameter(parameterization: Map[Parameter, Int])
extends Parameter {
	override def getValue = parameterization.keySet.map(p => parameterization(p) * p.getValue).reduceLeft((a, b) => a + b)
}

class YadasModel[M <: Measurement](network: Network[M],
	isInconsistency: Boolean)
extends ProgressObservable {
	val dichotomous: Boolean = {
		val cls = network.measurementType
		if (cls == classOf[DichotomousMeasurement])
			true
		else if (cls == classOf[ContinuousMeasurement])
			false
		else
			throw new IllegalStateException("Unknown measurement type " + cls)
	}

	private var ready = false

	protected var proto: NetworkModel[M] = null

	protected var parameters: Map[NetworkModelParameter, Parameter] = null

	private var parameterList: List[Parameter] = null
	private var updateList: List[MCMCUpdate] = null

	private var randomEffectVar: Parameter = null
	private var inconsistencyVar: Parameter = null

	private var burnInIter = 20000
	protected var simulationIter = 100000
	private var reportingInterval = 100

	def isReady = ready

	def run() {
		// construct model
		notifyModelConstructionStarted()
		buildModel()
		notifyModelConstructionFinished()

		// burn-in iterations
		notifyBurnInStarted()
		burnIn()
		notifyBurnInFinished()

		// simulation iterations
		notifySimulationStarted()
		simulate()

		// calculate results
		ready = true

		notifySimulationFinished()
	}

	def getRelativeEffect(base: Treatment, subj: Treatment): Estimate =
		paramEstimate(base, subj) match {
			case Some(x) => x
			case None => throw new IllegalArgumentException(
				"Treatment(s) not found")
		}


	def getInconsistencyFactors: java.util.List[InconsistencyParameter] = {
		val list = new java.util.ArrayList[InconsistencyParameter]()
		for (param <- proto.inconsistencyParameters) {
			list.add(param)
		}
		list
	}

	def getInconsistency(param: InconsistencyParameter): Estimate =
		parameters(param)

	def getBurnInIterations: Int = burnInIter

	def setBurnInIterations(it: Int) {
		validIt(it)
		burnInIter = it
	}

	def getSimulationIterations: Int = simulationIter

	def setSimulationIterations(it: Int) {
		validIt(it)
		simulationIter = it
	}


	private def validIt(it: Int) {
		if (it <= 0 || it % 100 != 0) throw new IllegalArgumentException("Specified # iterations should be a positive multiple of 100");
	}

	private def paramEstimate(base: Treatment, subj: Treatment)
	: Option[Estimate] =
		parameters.get(new BasicParameter(base, subj)) match {
			case Some(x: Estimate) => Some[Estimate](x)
			case None => negParamEstimate(subj, base)
		}

	private def negParamEstimate(base: Treatment, subj: Treatment)
	: Option[Estimate] =
		parameters.get(new BasicParameter(base, subj)) match {
			case Some(x: Estimate) =>
				Some[Estimate](new EstimateImpl(-x.getMean, x.getStandardDeviation))
			case None => None
		}

	private def sigmaPrior = {
		proto.variancePrior
	}

	private def inconsSigmaPrior = {
		sigmaPrior
	}

	private def buildModel() {
		if (proto == null) {
			proto = NetworkModel(network)
		}
		
		// study baselines
		val mu = new MCMCParameter(
			Array.make(proto.studyList.size, 0.0),
			Array.make(proto.studyList.size, 0.1),
			null)
		// random effects
		val delta = new MCMCParameter(
			Array.make(proto.relativeEffects.size, 0.0),
			Array.make(proto.relativeEffects.size, 0.1),
			null)
		// basic parameters
		val basic = new MCMCParameter(
			Array.make(proto.basicParameters.size, 0.0),
			Array.make(proto.basicParameters.size, 0.1),
			null)
		// inconsistency parameters
		val incons =
			if (isInconsistency)
				new MCMCParameter(
					Array.make(proto.inconsistencyParameters.size, 0.0),
					Array.make(proto.inconsistencyParameters.size, 0.1),
					null)
			else
				new MCMCParameter(
					Array.make(proto.inconsistencyParameters.size, 0.0),
					Array.make(proto.inconsistencyParameters.size, 0.0),
					null)
		// variance
		val sigma = new MCMCParameter(
			Array(0.25), Array(0.1), null)
		// inconsistency variance
		val sigmaw =
			if (isInconsistency)
				new MCMCParameter(Array(0.25), Array(0.1), null)
			else
				new MCMCParameter(Array(0.0), Array(0.0), null)

		val params =
			if (isInconsistency)
				List[MCMCParameter](mu, delta, basic, incons, sigma, sigmaw)
			else
				List[MCMCParameter](mu, delta, basic, sigma)

		// data bond
		val databond =
			if (dichotomous)
				dichotomousDataBond(
					proto.asInstanceOf[NetworkModel[DichotomousMeasurement]],
					mu, delta)
			else
				continuousDataBond(
					proto.asInstanceOf[NetworkModel[ContinuousMeasurement]],
					mu, delta)

		// random effects bound to basic/incons parameters
		val randomeffectbond =  new BasicMCMCBond(
				Array[MCMCParameter](delta, basic, incons, sigma),
				Array[ArgumentMaker](
					new IdentityArgument(0),
					new RelativeEffectArgumentMaker(proto, 1,
						if (isInconsistency) Some(2) else None),
					new GroupArgument(3, Array.make(proto.relativeEffects.size, 0))
				),
				new Gaussian()
			)

		val muprior = new BasicMCMCBond(
				Array[MCMCParameter](mu),
				Array[ArgumentMaker](
					new IdentityArgument(0),
					new ConstantArgument(0, proto.studyList.size),
					new ConstantArgument(Math.sqrt(1000), proto.studyList.size),
				),
				new Gaussian()
			)

		val basicprior = new BasicMCMCBond(
				Array[MCMCParameter](basic),
				Array[ArgumentMaker](
					new IdentityArgument(0),
					new ConstantArgument(0, proto.basicParameters.size),
					new ConstantArgument(Math.sqrt(1000), proto.basicParameters.size),
				),
				new Gaussian()
			)

		val sigmaprior = new BasicMCMCBond(
				Array[MCMCParameter](sigma),
				Array[ArgumentMaker](
					new IdentityArgument(0),
					new ConstantArgument(0.00001),
					new ConstantArgument(sigmaPrior)
				),
				new Uniform()
			)

		if (isInconsistency) {
			val inconsprior = new BasicMCMCBond(
					Array[MCMCParameter](incons, sigmaw),
					Array[ArgumentMaker](
						new IdentityArgument(0),
						new ConstantArgument(0, proto.inconsistencyParameters.size),
						new GroupArgument(1, Array.make(proto.inconsistencyParameters.size, 0))
					),
					new Gaussian()
				)

			val sigmawprior = new BasicMCMCBond(
					Array[MCMCParameter](sigmaw),
					Array[ArgumentMaker](
						new IdentityArgument(0),
						new ConstantArgument(0.00001),
						new ConstantArgument(inconsSigmaPrior)
					),
					new Uniform()
				)
			sigmawprior
		}

		def tuner(param: MCMCParameter): MCMCUpdate =
			new UpdateTuner(param, burnInIter / 50, 50, 1, Math.exp(-1))

		updateList = params.map(p => tuner(p))

		def paramList(p: MCMCParameter, n: Int): List[Parameter] =
			(0 until n).map(i => new DirectParameter(p, i)
				).toList

		val basicParam = paramList(basic, proto.basicParameters.size)
		val inconsParam = paramList(incons, proto.inconsistencyParameters.size)
		val sigmaParam = paramList(sigma, 1)
		val sigmawParam = paramList(sigmaw, 1)
		parameterList = basicParam ++ inconsParam ++ sigmaParam ++ sigmawParam

		val basicParamPairs = {
			for (i <- 0 until basicParam.size)
			yield (proto.basicParameters(i), basicParam(i))
		}
		val inconsParamPairs = {
			for (i <- 0 until inconsParam.size)
			yield (proto.inconsistencyParameters(i), inconsParam(i))
		}

		parameters = parameterMap(
			Map[NetworkModelParameter, Parameter]() ++
				basicParamPairs ++ inconsParamPairs)
		parameterList = parameters.values.toList ++ sigmaParam ++ sigmawParam
		
		randomEffectVar = sigmaParam(0)
		inconsistencyVar = sigmawParam(0)
	}

	private def parameterMap(basicMap: Map[NetworkModelParameter, Parameter])
	:Map[NetworkModelParameter, Parameter] = {
		val ts = proto.treatmentList
		basicMap ++ (
		for {i <- 0 until (ts.size - 1); j <- (i + 1) until ts.size;
			val p = new BasicParameter(ts(i), ts(j));
			if (!basicMap.keySet.contains(p))
		} yield (p, createIndirect(p, basicMap)))
	}

	private def createIndirect(p: BasicParameter,
			basicMap: Map[NetworkModelParameter, Parameter])
	: IndirectParameter = {
		val param = Map[Parameter, Int]() ++
			proto.parameterization(p.base, p.subject).map(
				(x) => (basicMap(x._1), x._2)).filter((x) => x._2 != 0)
		new IndirectParameter(param)
	}

	private def successArray(model: NetworkModel[DichotomousMeasurement])
	: Array[Double] =
		model.data.map(m => m._2.responders.toDouble).toArray

	private def sampleSizeArray(model: NetworkModel[_ <: Measurement])
	: Array[Double] =
		model.data.map(m => m._2.sampleSize.toDouble).toArray

	private def dichotomousDataBond(model: NetworkModel[DichotomousMeasurement],
			mu: MCMCParameter, delta: MCMCParameter)
	: BasicMCMCBond = {
		// success-rate r from data
		val r = new ConstantArgument(successArray(model))
		// sample-size n from data
		val n = new ConstantArgument(sampleSizeArray(model))

		// r_i ~ Binom(p_i, n_i) ; p_i = ilogit(theta_i) ;
		// theta_i = mu_s(i) + delta_s(i)b(i)t(i)
		new BasicMCMCBond(
				Array[MCMCParameter](mu, delta),
				Array[ArgumentMaker](
					r,
					n,
					new SuccessProbabilityArgumentMaker(model, 0, 1)
				),
				new Binomial()
			)
	}

	private def obsMeanArray(model: NetworkModel[ContinuousMeasurement])
	: Array[Double] =
		model.data.map(m => m._2.mean).toArray

	private def obsErrorArray(model: NetworkModel[ContinuousMeasurement])
	: Array[Double] =
		model.data.map(m => m._2.stdErr).toArray

	private def continuousDataBond(model: NetworkModel[ContinuousMeasurement],
			mu: MCMCParameter, delta: MCMCParameter)
	: BasicMCMCBond = {
		// success-rate r from data
		val m = new ConstantArgument(obsMeanArray(model))
		// sample-size n from data
		val s = new ConstantArgument(obsErrorArray(model))

		// m_i ~ N(theta_i, s_i)
		// theta_i = mu_s(i) + delta_s(i)b(i)t(i)
		new BasicMCMCBond(
				Array[MCMCParameter](mu, delta),
				Array[ArgumentMaker](
					m,
					new ThetaArgumentMaker(model, 0, 1),
					s,
				),
				new Gaussian()
			)
	}

	private def burnIn() {
		for (i <- 0 until burnInIter) {
			if (i % reportingInterval == 0 && i / reportingInterval > 0)
				notifyBurnInProgress(i);

			update()
		}
	}

	private def simulate() {
		for (i <- 0 until simulationIter) {
			if (i % reportingInterval == 0 && i / reportingInterval > 0)
				notifySimulationProgress(i);

			update()
			output()
		}
	}

	private def update() {
		for (u <- updateList) {
			u.update()
		}
	}

	protected def output() {
		for (p <- parameterList) {
			p.update()
		}
	}
}