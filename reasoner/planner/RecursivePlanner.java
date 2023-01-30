/*
 * Copyright (C) 2022 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.vaticle.typedb.core.reasoner.planner;

import com.vaticle.typedb.common.collection.Collections;
import com.vaticle.typedb.common.collection.Pair;
import com.vaticle.typedb.core.common.exception.TypeDBException;
import com.vaticle.typedb.core.concept.ConceptManager;
import com.vaticle.typedb.core.logic.LogicManager;
import com.vaticle.typedb.core.logic.resolvable.Concludable;
import com.vaticle.typedb.core.logic.resolvable.Resolvable;
import com.vaticle.typedb.core.logic.resolvable.ResolvableConjunction;
import com.vaticle.typedb.core.pattern.variable.Variable;
import com.vaticle.typedb.core.reasoner.planner.ConjunctionGraph.ConjunctionNode;
import com.vaticle.typedb.core.traversal.TraversalEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import static com.vaticle.typedb.core.common.exception.ErrorMessage.Internal.ILLEGAL_STATE;
import static com.vaticle.typedb.core.common.iterator.Iterators.iterate;

public class RecursivePlanner extends ReasonerPlanner {
    // Inaccuracies:
    //      retrieval costs are treated the same as reasoning-overhead cost (Both approximated as the number of answers retrieved for all variables)
    //      Excess calls are not penalised, since the scaling factor is capped at one (Solve using (calls + scaling-factor * rec-cost?) )
    //      Acyclic dependencies are counted once for each occurence
    //          (Can be fixed by including the optimal plans for the bound in the globalPlan chosenSummaries - The scaling factors will be capped at 1 then)
    // So far, these are acceptable because the difference in cost is some constant factor, and our aim is to avoid horrible plans.
    //
    //      !!! The cost does not depend on the binding mode !!! because of the formulation - Handle this with connectedness restriction when generating orders?

    final AnswerCountEstimator answerCountEstimator;
    private final Map<CallMode, Set<OrderingChoice>> orderingChoices;
    private final ConjunctionGraph conjunctionGraph;
    private final Map<CallMode, Double> cyclicScalingFactors;

    public RecursivePlanner(TraversalEngine traversalEng, ConceptManager conceptMgr, LogicManager logicMgr) {
        super(traversalEng, conceptMgr, logicMgr);
        this.conjunctionGraph = new ConjunctionGraph(logicMgr);
        this.answerCountEstimator = new AnswerCountEstimator(logicMgr, traversalEng.graph(), this.conjunctionGraph);
        this.orderingChoices = new HashMap<>();
        this.cyclicScalingFactors = new HashMap<>();
    }

    @Override
    Plan computePlan(CallMode callMode) {
        recursivelyGenerateOrderingChoices(callMode);
        planMutuallyRecursiveSubgraph(callMode);
        assert planCache.getIfPresent(callMode) != null;
        return planCache.getIfPresent(callMode);
    }

    // Conjunctions which call each other must be planned together
    private void planMutuallyRecursiveSubgraph(CallMode callMode) {
        Map<CallMode, OrderingChoice> choices = new HashMap<>();
        Set<CallMode> pendingModes = new HashSet<>();
        pendingModes.add(callMode);
        SubgraphPlan bestPlan = subgraphPlanSearch(callMode, pendingModes, choices);

        for (OrderingChoice bestPlanForCall : bestPlan.choices.values()) {
            planCache.put(bestPlanForCall.callMode, new Plan(bestPlanForCall.ordering, Math.round(Math.ceil(bestPlan.cost(bestPlanForCall.callMode, 1.0)))));
            cyclicScalingFactors.put(bestPlanForCall.callMode, bestPlan.cyclicScalingFactorSum.get(bestPlanForCall.callMode));
        }
    }

    private SubgraphPlan subgraphPlanSearch(CallMode root, Set<CallMode> pendingCallModes, Map<CallMode, OrderingChoice> choices) { // The value contains the scaling factors needed for the other conjunctions in the globalCost.
        // Pick a choice of ordering, expand dependencies, recurse ; backtrack over choices
        if (pendingCallModes.isEmpty()) { // All modes have an ordering chosen -> we have a complete candidate plan for the subgraph
            return SubgraphPlan.fromOrderingChoices(this, choices);
        }

        CallMode mode = iterate(pendingCallModes).next();
        pendingCallModes.remove(mode);
        assert !choices.containsKey(mode); // Should not have been added to pending

        ConjunctionNode conjunctionNode = conjunctionGraph.conjunctionNode(mode.conjunction);
        SubgraphPlan bestPlan = null; // for the branch of choices committed to so far.
        double bestPlanCost = Double.MAX_VALUE;
        for (OrderingChoice orderingChoice : orderingChoices.get(mode)) {
            choices.put(mode, orderingChoice);

            Set<CallMode> nextPendingModes = new HashSet<>(pendingCallModes);
            Set<CallMode> triggeredCalls = new HashSet<>();
            iterate(orderingChoice.cyclicConcludableModes).forEachRemaining(concludableMode -> {
                triggeredCalls.addAll(triggeredCalls(concludableMode.first(), concludableMode.second(), conjunctionNode.cyclicDependencies(concludableMode.first())));
            });
            iterate(triggeredCalls).filter(call -> !choices.containsKey(call)).forEachRemaining(nextPendingModes::add);
            SubgraphPlan newPlan = subgraphPlanSearch(root, nextPendingModes, choices);

            double newPlanCost = newPlan.cost(root, 1.0/choices.get(root).answersToMode);
            if (bestPlan == null || newPlanCost < bestPlanCost) {
                bestPlan = newPlan;
                bestPlanCost = newPlanCost;
            }

            choices.remove(mode);
        }
        assert bestPlan != null;
        return bestPlan;
    }

    private void recursivelyGenerateOrderingChoices(CallMode callMode) {
        if (!orderingChoices.containsKey(callMode)) {
            orderingChoices.put(callMode, null); // Guard
            ConjunctionNode conjunctionNode = conjunctionGraph.conjunctionNode(callMode.conjunction);
            answerCountEstimator.buildConjunctionModel(callMode.conjunction);

            Map<Set<Pair<Concludable, Set<Variable>>>, OrderingSummary> bestOrderings = new HashMap<>();
            PartialOrderReductionSearch porSearch = new PartialOrderReductionSearch(logicMgr.compile(callMode.conjunction), callMode.mode);
            for (List<Resolvable<?>> ordering : porSearch.allOrderings()) {
                initialiseOrderingDependencies(conjunctionNode, ordering, callMode.mode);
                OrderingSummary orderingSummary = createOrderingSummary(callMode, ordering);

                // Two orderings for the same CallMode with identical cyclic-concludable modes are interchangeable in the subgraph-plan
                //      -> We only need to keep the cheaper one.
                if (!bestOrderings.containsKey(orderingSummary.cyclicConcludableModes) ) {
                    bestOrderings.put(orderingSummary.cyclicConcludableModes, orderingSummary);
                } else {
                    OrderingSummary existingSummary = bestOrderings.get(orderingSummary.cyclicConcludableModes);
                    if (orderingSummary.singlyBoundCost < existingSummary.singlyBoundCost) {
                        bestOrderings.put(orderingSummary.cyclicConcludableModes, orderingSummary);
                    }
                }
            }
            this.orderingChoices.put(callMode, iterate(bestOrderings.values()).map(this::createOrderingChoice).toSet());
        }
    }

    private void initialiseOrderingDependencies(ConjunctionNode conjunctionNode, List<Resolvable<?>> ordering, Set<Variable> mode) {
        Set<Variable> currentBoundVars = new HashSet<>(mode);
        for (Resolvable<?> resolvable : ordering) {
            Set<Variable> resolvableMode = Collections.intersection(estimateableVariables(resolvable.variables()), currentBoundVars);
            initialiseResolvableDependencies(conjunctionNode, resolvable, resolvableMode);
            if (!resolvable.isNegated()) currentBoundVars.addAll(estimateableVariables(resolvable.variables()));
        }
    }

    private void initialiseResolvableDependencies(ConjunctionNode conjunctionNode, Resolvable<?> resolvable, Set<Variable> resolvableMode) {
        if (resolvable.isConcludable()) {
            Set<ResolvableConjunction> cyclicDependencies = conjunctionNode.cyclicDependencies(resolvable.asConcludable());
            for (CallMode callMode : triggeredCalls(resolvable.asConcludable(), resolvableMode, null)) {
                recursivelyGenerateOrderingChoices(callMode);
                if (!cyclicDependencies.contains(callMode.conjunction)) {
                    plan(callMode); // Acyclic dependencies can be fully planned
                }
            }
        } else if (resolvable.isNegated()) {
            iterate(resolvable.asNegated().disjunction().conjunctions()).forEachRemaining(conjunction -> {
                Set<Variable> branchVariables = Collections.intersection(estimateableVariables(conjunction.pattern().variables()), resolvableMode);
                CallMode callMode = new CallMode(conjunction, branchVariables);
                recursivelyGenerateOrderingChoices(callMode);
                plan(callMode);
            });
        }
    }

    // Functions which compute the cost of orderings
    private OrderingSummary createOrderingSummary(CallMode callMode, List<Resolvable<?>> ordering) {
        Set<Variable> boundVars = new HashSet<>(callMode.mode);  // bound -> in input mode or restricted locally
        ConjunctionNode conjunctionNode = conjunctionGraph.conjunctionNode(callMode.conjunction);

        AnswerCountEstimator.IncrementalEstimator estimator = answerCountEstimator.createIncrementalEstimator(conjunctionNode.conjunction(), callMode.mode);
        Set<Pair<Concludable, Set<Variable>>> cyclicConcludableModes = new HashSet<>();
        double singlyBoundCost = 0.0;
        for (Resolvable<?> resolvable : ordering) {
            Set<Variable> resolvableVars = estimateableVariables(resolvable.variables());
            Set<Variable> resolvableMode = Collections.intersection(resolvableVars, boundVars);
            double answersForModeFromPrefix = estimator.answerEstimate(resolvableMode);
            double resolvableCost;
            if (resolvable.isNegated()) {
                resolvableCost = iterate(resolvable.asNegated().disjunction().conjunctions()).map(conj -> {
                    Set<Variable> conjVariables = estimateableVariables(conj.pattern().variables());
                    double allAnswersForMode = answerCountEstimator.estimateAnswers(conj, Collections.intersection(conjVariables, resolvableMode));
                    double scalingFactor = allAnswersForMode !=0 ? Math.min(1, answersForModeFromPrefix / allAnswersForMode) : 0;
                    return scaledCallCost(scalingFactor, new CallMode(conj, Collections.intersection(conjVariables, resolvableMode)));
                }).reduce(0.0, Double::sum);
            } else {
                double allAnswersForMode = answerCountEstimator.localEstimate(callMode.conjunction, resolvable, resolvableMode);
                double scalingFactor = allAnswersForMode != 0 ? Math.min(1, answersForModeFromPrefix / allAnswersForMode) : 0;
                resolvableCost = scaledAcyclicCost(scalingFactor, conjunctionNode, resolvable, resolvableMode);
            }

            if (resolvable.isConcludable() && conjunctionNode.cyclicConcludables().contains(resolvable.asConcludable())) {
                cyclicConcludableModes.add(new Pair<>(resolvable.asConcludable(), resolvableMode));
            }

            estimator.extend(resolvable);
            singlyBoundCost += resolvableCost;

            if (!resolvable.isNegated()) {
                boundVars.addAll(resolvableVars);
            }
        }
        return new OrderingSummary(callMode, ordering, cyclicConcludableModes, singlyBoundCost);
    }

    private OrderingChoice createOrderingChoice(OrderingSummary summary) {
        ConjunctionNode conjunctionNode = conjunctionGraph.conjunctionNode(summary.callMode.conjunction);

        Set<Variable> boundVars = new HashSet<>(summary.callMode.mode);  // bound -> in input mode or restricted locally
        Set<Variable> restrictedVars = new HashSet<>(); // restricted -> Restricted by preceding resolvables
        Set<Variable> inputConnectedVars = new HashSet<>(summary.callMode.mode);
        double acyclicCost = 0.0;
        double unscalableCost = 0.0;

        Map<Concludable, Double> scalingFactors = new HashMap<>();
        AnswerCountEstimator.IncrementalEstimator estimator = answerCountEstimator.createIncrementalEstimator(conjunctionNode.conjunction());
        for (Resolvable<?> resolvable : summary.ordering) {
            Set<Variable> resolvableVars = estimateableVariables(resolvable.variables());
            Set<Variable> resolvableMode = Collections.intersection(resolvableVars, boundVars);
            Set<Variable> restrictedResolvableVars = Collections.intersection(resolvableVars, restrictedVars);

            double answersForModeFromPrefix = estimator.answerEstimate(restrictedResolvableVars);
            double resolvableCost;
            if (resolvable.isNegated()) {
                resolvableCost = iterate(resolvable.asNegated().disjunction().conjunctions()).map(conj -> {
                    Set<Variable> conjVariables = estimateableVariables(conj.pattern().variables());
                    double allAnswersForMode = answerCountEstimator.estimateAnswers(conj, Collections.intersection(conjVariables, restrictedResolvableVars));
                    double scalingFactor = allAnswersForMode !=0 ? Math.min(1, answersForModeFromPrefix / allAnswersForMode) : 0;
                    return scaledCallCost(scalingFactor, new CallMode(conj, Collections.intersection(conjVariables, resolvableMode)));
                }).reduce(0.0, Double::sum);
            } else {
                double allAnswersForMode = answerCountEstimator.localEstimate(conjunctionNode.conjunction(), resolvable, restrictedResolvableVars);
                double scalingFactor = allAnswersForMode != 0 ? Math.min(1, answersForModeFromPrefix / allAnswersForMode) : 0;
                resolvableCost = scaledAcyclicCost(scalingFactor, conjunctionNode, resolvable, resolvableMode);
            }

            if (resolvable.isConcludable() && conjunctionNode.cyclicConcludables().contains(resolvable.asConcludable())) {
                // Question: Do we project onto all the restrictedVars, or only those not in the mode?
                // Including those in the mode leads to double-scaling. Excluding them leads to an overestimate if the bounds are not restrictive.
                // I lean towards the overestimate.
                Set<Variable> projectionVars = iterate(restrictedResolvableVars).filter(v -> !summary.callMode.mode.contains(v)).toSet();
                long allAnswersForUnrestrictedMode = answerCountEstimator.localEstimate(conjunctionNode.conjunction(), resolvable, resolvableMode);
                double cyclicScalingFactor = projectionVars.isEmpty() && allAnswersForUnrestrictedMode != 0 ? 0.0 :
                        (double) estimator.answerEstimate(projectionVars) / allAnswersForUnrestrictedMode;
                scalingFactors.put(resolvable.asConcludable(), cyclicScalingFactor);
            }

            estimator.extend(resolvable);

            boolean isConnectedToInput = !Collections.intersection(resolvableMode, inputConnectedVars).isEmpty();
            if (isConnectedToInput) {
                acyclicCost += resolvableCost;
            } else {
                unscalableCost += resolvableCost;
            }

            if (!resolvable.isNegated()) {
                boundVars.addAll(resolvableVars);
                restrictedVars.addAll(resolvableVars);
                if (isConnectedToInput) inputConnectedVars.addAll(resolvableVars);
            }
        }

        double answersToMode = answerCountEstimator.estimateAnswers(summary.callMode.conjunction, summary.callMode.mode);
        return new OrderingChoice(summary.callMode, summary.ordering, summary.cyclicConcludableModes, scalingFactors, acyclicCost, unscalableCost, answersToMode);
    }

    private long retrievalCost(ResolvableConjunction conjunction, Resolvable<?> resolvable, Set<Variable> mode) {
        // Inaccurate because retrievables traversals work differently.
        // Also inaccurate because it considers inferred answers for concludables? We could rename to computeLocalCost.
        AnswerCountEstimator.IncrementalEstimator estimator = answerCountEstimator.createIncrementalEstimator(conjunction);
        estimator.extend(resolvable);
        return estimator.answerEstimate(estimateableVariables(resolvable.variables()));
    }

    private double scaledAcyclicCost(double scalingFactor, ConjunctionNode conjunctionNode, Resolvable<?> resolvable, Set<Variable> resolvableMode) {
        assert !resolvable.isNegated();
        double cost = 0.0;
        if (resolvable.isRetrievable()) {
            cost += scalingFactor * retrievalCost(conjunctionNode.conjunction(), resolvable, resolvableMode);
        } else if (resolvable.isConcludable()) {
            cost += scalingFactor * retrievalCost(conjunctionNode.conjunction(), resolvable, resolvableMode);
            Set<CallMode> acyclicCalls = triggeredCalls(resolvable.asConcludable(), resolvableMode, conjunctionNode.acyclicDependencies(resolvable.asConcludable()));
            cost += iterate(acyclicCalls).map(acylcicCall -> scaledCallCost(scalingFactor, acylcicCall)).reduce(0.0, Double::sum);    // Assumption: Decompose the global planning problems to SCCs
        } else throw TypeDBException.of(ILLEGAL_STATE);
        return cost;
    }

    private double scaledCallCost(double scalingFactor, CallMode callMode) {
        return getPlan(callMode).cost() * Math.min(1.0, scalingFactor + cyclicScalingFactors.get(callMode));
    }

    private static class SubgraphPlan {
        private final Map<CallMode, OrderingChoice> choices;
        private final Map<CallMode, Double> cyclicScalingFactorSum;

        private SubgraphPlan(Map<CallMode, OrderingChoice> choices, Map<CallMode, Double> cyclicScalingFactorSum) {
            this.choices = new HashMap<>(choices);
            this.cyclicScalingFactorSum = new HashMap<>(cyclicScalingFactorSum);
            this.choices.keySet().forEach(callMode -> this.cyclicScalingFactorSum.putIfAbsent(callMode, 0.0));
        }

        private double cost(CallMode root, double rootScalingFactor) {
            double cycleCost = 0L;
            for (OrderingChoice orderingChoice : choices.values()) {
                double scalingFactor = orderingChoice.callMode.equals(root) ?
                        Math.min(1.0, rootScalingFactor + cyclicScalingFactorSum.get(orderingChoice.callMode)) :
                        cyclicScalingFactorSum.get(orderingChoice.callMode);
                cycleCost += orderingChoice.acyclicCost * scalingFactor + orderingChoice.unscalableCost;
            }

            return cycleCost;
        }

        private static SubgraphPlan fromOrderingChoices(RecursivePlanner planner, Map<CallMode, OrderingChoice> orderingChoices) {
            Map<CallMode, Double> scalingFactorSum = new HashMap<>();
            for (OrderingChoice orderingChoice : orderingChoices.values()) {
                ConjunctionNode conjunctionNode = planner.conjunctionGraph.conjunctionNode(orderingChoice.callMode.conjunction);
                orderingChoice.cyclicConcludableModes.forEach(concludableMode -> {
                    iterate(planner.triggeredCalls(concludableMode.first(), concludableMode.second(), conjunctionNode.cyclicDependencies(concludableMode.first())))
                            .forEachRemaining(callMode -> {
                                scalingFactorSum.put(callMode, Math.min(1.0,
                                        scalingFactorSum.getOrDefault(callMode, 0.0) + orderingChoice.scalingFactors.get(concludableMode.first())));
                            });
                });
            }
            return new SubgraphPlan(orderingChoices, scalingFactorSum);
        }

    }

    static class OrderingSummary {
        // Created for every resolvable-ordering of a CallMode
        final CallMode callMode;
        final List<Resolvable<?>> ordering;
        final Set<Pair<Concludable, Set<Variable>>> cyclicConcludableModes;
        final double singlyBoundCost;

        OrderingSummary(CallMode callMode, List<Resolvable<?>> ordering, Set<Pair<Concludable, Set<Variable>>> cyclicConcludableModes, double singlyBoundCost) {
            this.callMode = callMode;
            this.ordering = ordering;
            this.cyclicConcludableModes = cyclicConcludableModes;
            this.singlyBoundCost = singlyBoundCost;
        }
    }

    static class OrderingChoice {
        // Created for one ordering per (CallMode, CyclicConcludableModes) - the one with lowest SinglyBoundCost.
        final CallMode callMode;
        final List<Resolvable<?>> ordering;
        final double acyclicCost;
        final Set<Pair<Concludable, Set<Variable>>> cyclicConcludableModes;
        final Map<Concludable, Double> scalingFactors;
        final double unscalableCost;
        final double answersToMode;

        private OrderingChoice(CallMode callMode, List<Resolvable<?>> ordering,
                               Set<Pair<Concludable, Set<Variable>>> cyclicConcludableModes,
                               Map<Concludable, Double> scalingFactors, double acyclicCost, double unscalableCost, double answersToMode) {
            this.callMode = callMode;
            this.ordering = ordering;
            this.acyclicCost = acyclicCost;
            this.cyclicConcludableModes = cyclicConcludableModes;
            this.scalingFactors = scalingFactors;
            this.unscalableCost = unscalableCost;
            this.answersToMode = answersToMode;
        }
    }

    static class PartialOrderReductionSearch {
        private final Set<Resolvable<?>> resolvables;
        private final Set<Variable> mode;
        private final Map<Resolvable<?>, Set<Variable>> dependencies;

        // TODO: Combine these when we fix ReasonerPlanner.dependencies
        PartialOrderReductionSearch(Set<Resolvable<?>> resolvables, Set<Variable> mode) {
            this(resolvables, mode, ReasonerPlanner.dependencies(resolvables));
        }

        PartialOrderReductionSearch(Set<Resolvable<?>> resolvables, Set<Variable> mode, Map<Resolvable<?>, Set<Variable>> dependencies) {
            this.resolvables = resolvables;
            this.mode = mode;
            this.dependencies = dependencies;
        }

        List<List<Resolvable<?>>> allOrderings() {
            Set<Resolvable<?>> remaining = new HashSet<>(resolvables);
            Set<Variable> restrictedVars = new HashSet<>(mode);
            iterate(resolvables).filter(resolvable -> !resolvable.isNegated()).flatMap(r -> iterate(estimateableVariables(r.variables())))
                    .filter(v -> iterate(v.constraints()).anyMatch(constraint ->
                            constraint.isThing() && constraint.asThing().isValue() && constraint.asThing().asValue().isValueIdentity()))
                    .forEachRemaining(restrictedVars::add);

            List<List<Resolvable<?>>> orderings = new ArrayList<>();
            porDFS(new Stack<>(), restrictedVars, remaining, new HashSet<>(), orderings);
            assert !orderings.isEmpty();
            return orderings; // TODO: If this causes a memory blow-up, POR can "generate" one at a time.
        }

        private void porDFS(Stack<Resolvable<?>> currentPath, Set<Variable> currentBoundVars,
                            Set<Resolvable<?>> remaining, Set<Resolvable<?>> sleepSet, List<List<Resolvable<?>>> orderings) {
            if (remaining.isEmpty()) {
                orderings.add(new ArrayList<>(currentPath));
                return;
            }

            List<Resolvable<?>> enabled = iterate(remaining)
                    .filter(r -> ReasonerPlanner.dependenciesSatisfied(r, currentBoundVars, dependencies) && !sleepSet.contains(r))
                    .toList();

            if (enabled.isEmpty()) {
                return; // All enabled are sleeping
            }

            // Restrict further to only the connected ones
            List<Resolvable<?>> connectedEnabled = iterate(enabled)
                    .filter(r -> iterate(estimateableVariables(r.variables())).anyMatch(currentBoundVars::contains)).toList();

            if (!connectedEnabled.isEmpty()) {
                enabled = connectedEnabled;
            } else if (connectedEnabled.isEmpty() && !sleepSet.isEmpty()) {
                return; // We should have tried the disconnected down a path that's now sleeping.
            } // else enabled = enabled; and carry on

            Set<Resolvable<?>> newSleepSet = new HashSet<>(sleepSet);
            for (Resolvable<?> next : enabled) {
                Set<Resolvable<?>> awaken = iterate(newSleepSet)
                        .filter(r -> iterate(r.variables()).anyMatch(next.variables()::contains)) // not necessarily newly bound
                        .toSet();

                Set<Resolvable<?>> nextSleepSet = iterate(newSleepSet).filter(r -> !awaken.contains(r)).toSet();
                Set<Variable> newlyBoundVars = next.isNegated() ?
                        new HashSet<>() :
                        iterate(next.variables()).filter(v -> !currentBoundVars.contains(v)).toSet();

                currentPath.add(next);
                remaining.remove(next);
                currentBoundVars.addAll(newlyBoundVars);
                porDFS(currentPath, currentBoundVars, remaining, nextSleepSet, orderings);
                currentBoundVars.removeAll(newlyBoundVars);
                remaining.add(next);
                currentPath.remove(currentPath.size() - 1);

                newSleepSet.add(next);
            }
        }
    }
}
