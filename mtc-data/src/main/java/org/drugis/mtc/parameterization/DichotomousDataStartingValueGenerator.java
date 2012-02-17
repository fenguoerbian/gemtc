package org.drugis.mtc.parameterization;


import org.apache.commons.math.random.RandomGenerator;
import org.drugis.common.stat.DichotomousDescriptives;
import org.drugis.common.stat.EstimateWithPrecision;
import org.drugis.common.stat.Statistics;
import org.drugis.mtc.data.DataType;
import org.drugis.mtc.model.Measurement;
import org.drugis.mtc.model.Network;
import org.drugis.mtc.model.Study;
import org.drugis.mtc.model.Treatment;

import edu.uci.ics.jung.algorithms.transformation.FoldingTransformerFixed.FoldedEdge;
import edu.uci.ics.jung.graph.UndirectedGraph;


/**
 * Generate starting values for a network with dichotomous data.
 */
public class DichotomousDataStartingValueGenerator extends AbstractDataStartingValueGenerator {
	/**
	 * Create a deterministic starting value generator.
	 * @param network Network to generate starting values for.
	 */
	public DichotomousDataStartingValueGenerator(Network network, UndirectedGraph<Treatment, FoldedEdge<Treatment, Study>> cGraph) {
		super(network, cGraph, null, 0.0);
		if (!network.getType().equals(DataType.RATE)) {
			throw new IllegalArgumentException("The network must be RATE");
		}
	}
	
	/**
	 * Create a randomized starting value generator.
	 * @param network Network to generate starting values for.
	 * @param rng The random generator to use.
	 * @param scale Scaling factor for the second moment of the error distribution.
	 */	
	public DichotomousDataStartingValueGenerator(Network network, UndirectedGraph<Treatment, FoldedEdge<Treatment, Study>> cGraph, RandomGenerator rng, double scale) {
		super(network, cGraph, rng, scale);
		if (!network.getType().equals(DataType.RATE)) {
			throw new IllegalArgumentException("The network must be RATE");
		}
	}
	
	@Override
	protected EstimateWithPrecision estimateTreatmentEffect(Study study, Treatment treatment) {
		Measurement m = NetworkModel.findMeasurement(study, treatment);
		double mean = new DichotomousDescriptives(true).logOdds(m.getResponders(), m.getSampleSize());
		double se = Math.sqrt(1 / (m.getResponders() + 0.5) + 1 / (m.getSampleSize() - m.getResponders() + 0.5));
		return new EstimateWithPrecision(mean, se);
	}

	@Override
	protected EstimateWithPrecision estimateRelativeEffect(Study study, BasicParameter p) {
		Measurement m0 = NetworkModel.findMeasurement(study, p.getBaseline());
		Measurement m1 = NetworkModel.findMeasurement(study, p.getSubject());
		return Statistics.logOddsRatio(m0.getResponders(), m0.getSampleSize(), m1.getResponders(), m1.getSampleSize(), true);
	}

}
