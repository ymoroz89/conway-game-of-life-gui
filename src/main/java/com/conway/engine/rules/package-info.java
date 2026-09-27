/**
 * Pure generation computation: rules, partitioning and its failure type.
 *
 * <p>No threads of its own, no Swing, no mutable board ownership.
 * {@link com.conway.engine.rules.LifeRules} schedules a generation across row bands on a
 * borrowed executor, {@code BandKernel} applies the rules to one band,
 * {@link com.conway.engine.rules.PartitionPlanner} sizes the work, and
 * {@link com.conway.engine.rules.SimulationException} reports a failed generation.
 */
package com.conway.engine.rules;
