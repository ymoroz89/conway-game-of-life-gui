/**
 * Pure generation computation: rules, partitioning and its failure type.
 *
 * <p>No threads, no Swing, no mutable board ownership. {@link com.conway.engine.rules.LifeRules}
 * computes the next generation, {@link com.conway.engine.rules.PartitionPlanner} sizes the
 * work, {@link com.conway.engine.rules.SimulationException} reports a failed generation.
 */
package com.conway.engine.rules;
