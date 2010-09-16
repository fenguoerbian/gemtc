/*
 * This file is part of drugis.org MTC.
 * MTC is distributed from http://drugis.org/mtc.
 * Copyright (C) 2009-2010 Gert van Valkenhoef.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.drugis.mtc

import org.apache.commons.math.stat.descriptive.rank.Percentile

trait NetworkModelParameter {

}

final class BasicParameter(val base: Treatment, val subject: Treatment)
extends NetworkModelParameter {
	override def toString() = "d." + base.id + "." + subject.id

	override def equals(other: Any): Boolean = other match {
		case p: BasicParameter => (p.base == base && p.subject == subject)
		case _ => false
	}

	override def hashCode: Int = 31 * base.hashCode + subject.hashCode
}

final class InconsistencyParameter(val cycle: List[Treatment])
extends NetworkModelParameter {
	def this(list: java.util.List[Treatment]) {
		this(scala.collection.jcl.Conversions.convertList(list).toList)
	}

	private val cycleStr =
		cycle.reverse.tail.reverse.map(t => t.id).mkString(".")

	override def toString() = "w." + cycleStr

	override def equals(other: Any): Boolean = other match {
		case p: InconsistencyParameter => (p.cycle == cycle)
		case _ => false
	}

	override def hashCode: Int = cycle.hashCode

	def treatmentList: java.util.List[Treatment] = {
		val list = new java.util.ArrayList[Treatment]()
		for (t <- cycle) {
			list.add(t)
		}
		list
	}
}

/**
 * Class representing a Bayes model for a treatment network
 */
class NetworkModel[M <: Measurement](
	val parametrization: InconsistencyParametrization[M],
	val studyBaseline: Map[Study[M], Treatment],
	val treatmentList: List[Treatment],
	val studyList: List[Study[M]]) {

	val network: Network[M] = parametrization.network
	val basis: FundamentalGraphBasis[Treatment] = parametrization.basis

	require(Set[Study[M]]() ++ studyList == network.studies)
	require(Set[Treatment]() ++ treatmentList == network.treatments)
	require(studyBaseline.keySet == network.studies)
	require(basis.tree.vertexSet == network.treatments)

	def this(
			network: Network[M],
			basis: FundamentalGraphBasis[Treatment],
			studyBaseline: Map[Study[M], Treatment],
			treatmentList: List[Treatment],
			studyList: List[Study[M]]) {
		this(new InconsistencyParametrization(network, basis), studyBaseline,
			treatmentList, studyList)
	}

	val studyMap = NetworkModel.indexMap(studyList)
	val treatmentMap = NetworkModel.indexMap(treatmentList)

	val data = studyList.flatMap(
		study => NetworkModel.treatmentList(study.treatments).map(
				t => (study, study.measurements(t))))

	/**
	 * Gives the list of Inconsistency parameters
	 */
	val inconsistencyParameters: List[InconsistencyParameter] =
		parametrization.inconsistencyParameters

	/**
	 * Gives the list of Basic parameters
	 */
	val basicParameters: List[BasicParameter] =
		parametrization.basicParameters

	/**
	 * Full list of parameters
	 */
	val parameterVector: List[NetworkModelParameter] =
		List[NetworkModelParameter]() ::: basicParameters :::
			inconsistencyParameters

	/**
	 * List of relative effects in order
	 */
	val relativeEffects: List[(Treatment, Treatment)] =
		studyList.flatMap(study => studyRelativeEffects(study))

	val relativeEffectIndex: Map[Study[M], Int] =
		reIndexMap(studyList, 0)

	private def reIndexMap(l: List[Study[M]], i: Int)
	: Map[Study[M], Int] = l match {
		case Nil => Map[Study[M], Int]()
		case s :: l0 =>
			reIndexMap(l0, i + s.treatments.size - 1) + ((s, i))
	}

	def studyRelativeEffects(study: Study[M])
	: List[(Treatment, Treatment)] =
		for {t <- treatmentList; if (study.treatments.contains(t)
				&& !(studyBaseline(study) == t))
			} yield (studyBaseline(study), t)

	def variancePrior: Double = {
		val cls = network.measurementType
		val means =
			if (cls == classOf[DichotomousMeasurement])
				dichMeans()
			else if (cls == classOf[ContinuousMeasurement])
				contMeans()
			else
				throw new IllegalStateException("Unknown measurement type " +
						cls)
		2 * iqr(means)
	}

	private def iqr(x: List[Double]): Double = {
		// Percentile implementation corresponds to type=6 quantile in R
		val p25 = new Percentile(25)
		val p75 = new Percentile(75)
		p75.evaluate(x.toArray) - p25.evaluate(x.toArray)
	}

	private def dichMeans(): List[Double] = {
		for {m <- data} yield
			logOdds(m._2.asInstanceOf[DichotomousMeasurement])
	}

	private def logOdds(m: DichotomousMeasurement): Double = {
		val p = (if (m.responders == 0) 0.5 else m.responders.toDouble) /
			m.sampleSize.toDouble
		Math.log(p / (1 - p))
	}

	private def contMeans(): List[Double] = {
		for {m <- data} yield
			m._2.asInstanceOf[ContinuousMeasurement].mean
	}
}

object NetworkModel {
	def apply[M <: Measurement](network: Network[M], tree: Tree[Treatment])
	: NetworkModel[M] = {
		val pmtz = new InconsistencyParametrization(network,
			new FundamentalGraphBasis(network.treatmentGraph, tree))
		new NetworkModel[M](pmtz,
			assignBaselines(pmtz),
			treatmentList(network.treatments),
			studyList(network.studies))
	}

	def apply[M <: Measurement](network: Network[M], base: Treatment)
	: NetworkModel[M] = {
		apply(network, network.bestSpanningTree(base))
	}

	def apply[M <: Measurement](network: Network[M]): NetworkModel[M] = {
		apply(network, treatmentList(network.treatments).first)
	}

	def assignBaselines[M <: Measurement](pmtz: InconsistencyParametrization[M])
	: Map[Study[M], Treatment] = {
		val alg = new DFS()
		alg.search(BaselineSearchProblem(pmtz)) match {
			case None => throw new Exception("No Assignment Found!")
			case Some(x) => x.assignment
		}
	}

	def studyList[M <: Measurement](studies: Set[Study[M]]) = {
		studies.toList.sort((a, b) => a.id < b.id)
	}

	def treatmentList(treatments: Set[Treatment]) = {
		treatments.toList.sort((a, b) => a < b)
	}

	def indexMap[T](l: List[T]): Map[T, Int] =
		Map[T, Int]() ++ l.map(a => (a, l.indexOf(a) + 1))
}
