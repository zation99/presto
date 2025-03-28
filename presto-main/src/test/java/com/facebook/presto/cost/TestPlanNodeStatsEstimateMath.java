/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.cost;

import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.statistics.ConnectorHistogram;
import com.facebook.presto.spi.statistics.DisjointRangeDomainHistogram;
import com.facebook.presto.spi.statistics.UniformDistributionHistogram;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.function.BiFunction;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.testing.assertions.Assert.assertEquals;
import static java.lang.Double.NEGATIVE_INFINITY;
import static java.lang.Double.NaN;
import static java.lang.Double.POSITIVE_INFINITY;

public class TestPlanNodeStatsEstimateMath
{
    private static final VariableReferenceExpression VARIABLE = new VariableReferenceExpression(Optional.empty(), "variable", BIGINT);
    private static final StatisticRange NON_EMPTY_RANGE = openRange(1);
    private final PlanNodeStatsEstimateMath calculator = new PlanNodeStatsEstimateMath(true);

    @Test
    public void testAddRowCount()
    {
        PlanNodeStatsEstimate unknownStats = statistics(NaN, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate first = statistics(10, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate second = statistics(20, NaN, NaN, NaN, StatisticRange.empty());

        assertEquals(calculator.addStatsAndSumDistinctValues(unknownStats, unknownStats), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.addStatsAndSumDistinctValues(first, unknownStats), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.addStatsAndSumDistinctValues(unknownStats, second), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.addStatsAndSumDistinctValues(first, second).getOutputRowCount(), 30.0);
    }

    @Test
    public void testAddTotalSize()
    {
        PlanNodeStatsEstimate unknownStats = statistics(NaN, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate first = statistics(NaN, 10, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate second = statistics(NaN, 20, NaN, NaN, StatisticRange.empty());

        assertEquals(calculator.addStatsAndSumDistinctValues(unknownStats, unknownStats), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.addStatsAndSumDistinctValues(first, unknownStats), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.addStatsAndSumDistinctValues(unknownStats, second), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.addStatsAndSumDistinctValues(first, second).getTotalSize(), 30.0);
    }

    @Test
    public void testAddNullsFraction()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, 0.1, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownNullsFraction = statistics(10, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate first = statistics(10, NaN, 0.1, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate second = statistics(20, NaN, 0.2, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate fractionalRowCountFirst = statistics(0.1, NaN, 0.1, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate fractionalRowCountSecond = statistics(0.2, NaN, 0.3, NaN, NON_EMPTY_RANGE);

        assertAddNullsFraction(unknownRowCount, unknownRowCount, NaN);
        assertAddNullsFraction(unknownNullsFraction, unknownNullsFraction, NaN);
        assertAddNullsFraction(unknownRowCount, unknownNullsFraction, NaN);
        assertAddNullsFraction(first, unknownNullsFraction, NaN);
        assertAddNullsFraction(unknownRowCount, second, NaN);
        assertAddNullsFraction(first, second, 0.16666666666666666);
        assertAddNullsFraction(fractionalRowCountFirst, fractionalRowCountSecond, 0.2333333333333333);
    }

    private void assertAddNullsFraction(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, double expected)
    {
        assertEquals(calculator.addStatsAndSumDistinctValues(first, second).getVariableStatistics(VARIABLE).getNullsFraction(), expected);
    }

    @Test
    public void testAddAverageRowSize()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, 0.1, 10, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownNullsFraction = statistics(10, NaN, NaN, 10, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownAverageRowSize = statistics(10, NaN, 0.1, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate first = statistics(10, NaN, 0.1, 15, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate second = statistics(20, NaN, 0.2, 20, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate fractionalRowCountFirst = statistics(0.1, NaN, 0.1, 0.3, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate fractionalRowCountSecond = statistics(0.2, NaN, 0.3, 0.4, NON_EMPTY_RANGE);

        assertAddAverageRowSize(unknownRowCount, unknownRowCount, NaN);
        assertAddAverageRowSize(unknownNullsFraction, unknownNullsFraction, NaN);
        assertAddAverageRowSize(unknownAverageRowSize, unknownAverageRowSize, NaN);
        assertAddAverageRowSize(first, unknownRowCount, NaN);
        assertAddAverageRowSize(unknownNullsFraction, second, NaN);
        assertAddAverageRowSize(first, unknownAverageRowSize, NaN);
        assertAddAverageRowSize(first, second, 18.2);
        assertAddAverageRowSize(fractionalRowCountFirst, fractionalRowCountSecond, 0.3608695652173913);
    }

    private void assertAddAverageRowSize(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, double expected)
    {
        assertEquals(calculator.addStatsAndSumDistinctValues(first, second).getVariableStatistics(VARIABLE).getAverageRowSize(), expected);
    }

    @Test
    public void testSumNumberOfDistinctValues()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate emptyRange = statistics(10, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate unknownRange = statistics(10, NaN, NaN, NaN, openRange(NaN));
        PlanNodeStatsEstimate first = statistics(10, NaN, NaN, NaN, openRange(2));
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, NaN, openRange(3));

        assertSumNumberOfDistinctValues(unknownRowCount, unknownRowCount, NaN);
        assertSumNumberOfDistinctValues(unknownRowCount, second, NaN);
        assertSumNumberOfDistinctValues(first, emptyRange, 2);
        assertSumNumberOfDistinctValues(first, unknownRange, NaN);
        assertSumNumberOfDistinctValues(first, second, 5);
    }

    private void assertSumNumberOfDistinctValues(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, double expected)
    {
        assertEquals(calculator.addStatsAndSumDistinctValues(first, second).getVariableStatistics(VARIABLE).getDistinctValuesCount(), expected);
    }

    @Test
    public void testMaxNumberOfDistinctValues()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate emptyRange = statistics(10, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate unknownRange = statistics(10, NaN, NaN, NaN, openRange(NaN));
        PlanNodeStatsEstimate first = statistics(10, NaN, NaN, NaN, openRange(2));
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, NaN, openRange(3));

        assertMaxNumberOfDistinctValues(unknownRowCount, unknownRowCount, NaN);
        assertMaxNumberOfDistinctValues(unknownRowCount, second, NaN);
        assertMaxNumberOfDistinctValues(first, emptyRange, 2);
        assertMaxNumberOfDistinctValues(first, unknownRange, NaN);
        assertMaxNumberOfDistinctValues(first, second, 3);
    }

    private void assertMaxNumberOfDistinctValues(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, double expected)
    {
        assertEquals(calculator.addStatsAndMaxDistinctValues(first, second).getVariableStatistics(VARIABLE).getDistinctValuesCount(), expected);
    }

    @Test
    public void testAddRange()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate emptyRange = statistics(10, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate unknownRange = statistics(10, NaN, NaN, NaN, openRange(NaN));
        PlanNodeStatsEstimate first = statistics(10, NaN, NaN, NaN, new StatisticRange(12, 100, 2));
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, NaN, new StatisticRange(101, 200, 3));

        assertAddRange(unknownRange, unknownRange, NEGATIVE_INFINITY, POSITIVE_INFINITY);
        assertAddRange(unknownRowCount, second, NEGATIVE_INFINITY, POSITIVE_INFINITY);
        assertAddRange(unknownRange, second, NEGATIVE_INFINITY, POSITIVE_INFINITY);
        assertAddRange(emptyRange, second, 101, 200);
        assertAddRange(first, second, 12, 200);
    }

    private void assertAddRange(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, double expectedLow, double expectedHigh)
    {
        VariableStatsEstimate statistics = calculator.addStatsAndMaxDistinctValues(first, second).getVariableStatistics(VARIABLE);
        assertEquals(statistics.getLowValue(), expectedLow);
        assertEquals(statistics.getHighValue(), expectedHigh);
    }

    @Test
    public void testSubtractRowCount()
    {
        PlanNodeStatsEstimate unknownStats = statistics(NaN, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate first = statistics(40, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, NaN, StatisticRange.empty());

        assertEquals(calculator.subtractSubsetStats(unknownStats, unknownStats), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.subtractSubsetStats(first, unknownStats), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.subtractSubsetStats(unknownStats, second), PlanNodeStatsEstimate.unknown());
        assertEquals(calculator.subtractSubsetStats(first, second).getOutputRowCount(), 30.0);
    }

    @Test
    public void testSubtractNullsFraction()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, 0.1, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownNullsFraction = statistics(10, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate first = statistics(50, NaN, 0.1, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate second = statistics(20, NaN, 0.2, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate fractionalRowCountFirst = statistics(0.7, NaN, 0.1, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate fractionalRowCountSecond = statistics(0.2, NaN, 0.3, NaN, NON_EMPTY_RANGE);

        assertSubtractNullsFraction(unknownRowCount, unknownRowCount, NaN);
        assertSubtractNullsFraction(unknownRowCount, unknownNullsFraction, NaN);
        assertSubtractNullsFraction(first, unknownNullsFraction, NaN);
        assertSubtractNullsFraction(unknownRowCount, second, NaN);
        assertSubtractNullsFraction(first, second, 0.03333333333333333);
        assertSubtractNullsFraction(fractionalRowCountFirst, fractionalRowCountSecond, 0.019999999999999993);
    }

    private void assertSubtractNullsFraction(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, double expected)
    {
        assertEquals(calculator.subtractSubsetStats(first, second).getVariableStatistics(VARIABLE).getNullsFraction(), expected);
    }

    @Test
    public void testSubtractNumberOfDistinctValues()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownDistinctValues = statistics(100, NaN, 0.1, NaN, openRange(NaN));
        PlanNodeStatsEstimate zero = statistics(0, NaN, 0.1, NaN, openRange(0));
        PlanNodeStatsEstimate first = statistics(30, NaN, 0.1, NaN, openRange(10));
        PlanNodeStatsEstimate second = statistics(20, NaN, 0.1, NaN, openRange(5));
        PlanNodeStatsEstimate third = statistics(10, NaN, 0.1, NaN, openRange(3));

        assertSubtractNumberOfDistinctValues(unknownRowCount, unknownRowCount, NaN);
        assertSubtractNumberOfDistinctValues(unknownRowCount, second, NaN);
        assertSubtractNumberOfDistinctValues(unknownDistinctValues, second, NaN);
        assertSubtractNumberOfDistinctValues(first, zero, 10);
        assertSubtractNumberOfDistinctValues(zero, zero, 0);
        assertSubtractNumberOfDistinctValues(first, second, 5);
        assertSubtractNumberOfDistinctValues(second, third, 5);
    }

    private void assertSubtractNumberOfDistinctValues(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, double expected)
    {
        assertEquals(calculator.subtractSubsetStats(first, second).getVariableStatistics(VARIABLE).getDistinctValuesCount(), expected);
    }

    @Test
    public void testSubtractRange()
    {
        assertSubtractRange(NEGATIVE_INFINITY, POSITIVE_INFINITY, NEGATIVE_INFINITY, POSITIVE_INFINITY, NEGATIVE_INFINITY, POSITIVE_INFINITY);
        assertSubtractRange(0, 1, NEGATIVE_INFINITY, POSITIVE_INFINITY, 0, 1);
        assertSubtractRange(NaN, NaN, 0, 1, NaN, NaN);
        assertSubtractRange(0, 1, NaN, NaN, 0, 1);
        assertSubtractRange(0, 2, 0, 1, 0, 2);
        assertSubtractRange(0, 2, 1, 2, 0, 2);
        assertSubtractRange(0, 2, 0.5, 1, 0, 2);
    }

    private void assertSubtractRange(double supersetLow, double supersetHigh, double subsetLow, double subsetHigh, double expectedLow, double expectedHigh)
    {
        PlanNodeStatsEstimate first = statistics(30, NaN, NaN, NaN, new StatisticRange(supersetLow, supersetHigh, 10));
        PlanNodeStatsEstimate second = statistics(20, NaN, NaN, NaN, new StatisticRange(subsetLow, subsetHigh, 5));
        VariableStatsEstimate statistics = calculator.subtractSubsetStats(first, second).getVariableStatistics(VARIABLE);
        assertEquals(statistics.getLowValue(), expectedLow);
        assertEquals(statistics.getHighValue(), expectedHigh);
    }

    @Test
    public void testCapRowCount()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate first = statistics(20, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, NaN, NON_EMPTY_RANGE);

        assertEquals(calculator.capStats(unknownRowCount, unknownRowCount).getOutputRowCount(), NaN);
        assertEquals(calculator.capStats(first, unknownRowCount).getOutputRowCount(), NaN);
        assertEquals(calculator.capStats(unknownRowCount, second).getOutputRowCount(), NaN);
        assertEquals(calculator.capStats(first, second).getOutputRowCount(), 10.0);
        assertEquals(calculator.capStats(second, first).getOutputRowCount(), 10.0);
    }

    @Test
    public void testCapAverageRowSize()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownAverageRowSize = statistics(20, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate first = statistics(20, NaN, NaN, 10, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, 5, NON_EMPTY_RANGE);

        assertCapAverageRowSize(unknownRowCount, unknownRowCount, NaN);
        assertCapAverageRowSize(unknownAverageRowSize, unknownAverageRowSize, NaN);
        // average row size should be preserved
        assertCapAverageRowSize(first, unknownAverageRowSize, 10);
        assertCapAverageRowSize(unknownAverageRowSize, second, NaN);
        // average row size should be preserved
        assertCapAverageRowSize(first, second, 10);
    }

    private void assertCapAverageRowSize(PlanNodeStatsEstimate stats, PlanNodeStatsEstimate cap, double expected)
    {
        assertEquals(calculator.capStats(stats, cap).getVariableStatistics(VARIABLE).getAverageRowSize(), expected);
    }

    @Test
    public void testCapNumberOfDistinctValues()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownNumberOfDistinctValues = statistics(20, NaN, NaN, NaN, openRange(NaN));
        PlanNodeStatsEstimate first = statistics(20, NaN, NaN, NaN, openRange(10));
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, NaN, openRange(5));

        assertCapNumberOfDistinctValues(unknownRowCount, unknownRowCount, NaN);
        assertCapNumberOfDistinctValues(unknownNumberOfDistinctValues, unknownNumberOfDistinctValues, NaN);
        assertCapNumberOfDistinctValues(first, unknownRowCount, NaN);
        assertCapNumberOfDistinctValues(unknownNumberOfDistinctValues, second, NaN);
        assertCapNumberOfDistinctValues(first, second, 5);
    }

    private void assertCapNumberOfDistinctValues(PlanNodeStatsEstimate stats, PlanNodeStatsEstimate cap, double expected)
    {
        assertEquals(calculator.capStats(stats, cap).getVariableStatistics(VARIABLE).getDistinctValuesCount(), expected);
    }

    @Test
    public void testCapRange()
    {
        PlanNodeStatsEstimate emptyRange = statistics(10, NaN, NaN, NaN, StatisticRange.empty());
        PlanNodeStatsEstimate openRange = statistics(10, NaN, NaN, NaN, openRange(NaN));
        PlanNodeStatsEstimate first = statistics(10, NaN, NaN, NaN, new StatisticRange(12, 100, NaN));
        PlanNodeStatsEstimate second = statistics(10, NaN, NaN, NaN, new StatisticRange(13, 99, NaN));

        assertCapRange(emptyRange, emptyRange, NaN, NaN);
        assertCapRange(emptyRange, openRange, NaN, NaN);
        assertCapRange(openRange, emptyRange, NaN, NaN);
        assertCapRange(first, openRange, 12, 100);
        assertCapRange(openRange, second, 13, 99);
        assertCapRange(first, second, 13, 99);
    }

    private void assertCapRange(PlanNodeStatsEstimate stats, PlanNodeStatsEstimate cap, double expectedLow, double expectedHigh)
    {
        VariableStatsEstimate symbolStats = calculator.capStats(stats, cap).getVariableStatistics(VARIABLE);
        assertEquals(symbolStats.getLowValue(), expectedLow);
        assertEquals(symbolStats.getHighValue(), expectedHigh);
    }

    @Test
    public void testCapNullsFraction()
    {
        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate unknownNullsFraction = statistics(10, NaN, NaN, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate first = statistics(20, NaN, 0.25, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate second = statistics(10, NaN, 0.6, NaN, NON_EMPTY_RANGE);
        PlanNodeStatsEstimate third = statistics(0, NaN, 0.6, NaN, NON_EMPTY_RANGE);

        assertCapNullsFraction(unknownRowCount, unknownRowCount, NaN);
        assertCapNullsFraction(unknownNullsFraction, unknownNullsFraction, NaN);
        assertCapNullsFraction(first, unknownNullsFraction, NaN);
        assertCapNullsFraction(unknownNullsFraction, second, NaN);
        assertCapNullsFraction(first, second, 0.5);
        assertCapNullsFraction(first, third, 1);
    }

    private void assertCapNullsFraction(PlanNodeStatsEstimate stats, PlanNodeStatsEstimate cap, double expected)
    {
        assertEquals(calculator.capStats(stats, cap).getVariableStatistics(VARIABLE).getNullsFraction(), expected);
    }

    @Test
    public void testAddHistograms()
    {
        StatisticRange zeroToTen = new StatisticRange(0, 10, 1);
        StatisticRange zeroToFive = new StatisticRange(0, 5, 1);
        StatisticRange fiveToTen = new StatisticRange(5, 10, 1);
        StatisticRange threeToSeven = new StatisticRange(3, 7, 1);

        PlanNodeStatsEstimate unknownRowCount = statistics(NaN, NaN, NaN, NaN, zeroToTen);
        PlanNodeStatsEstimate unknownNullsFraction = statistics(10, NaN, NaN, NaN, zeroToTen);
        PlanNodeStatsEstimate first = statistics(50, NaN, 0.25, NaN, zeroToTen);
        PlanNodeStatsEstimate second = statistics(25, NaN, 0.6, NaN, zeroToFive);
        PlanNodeStatsEstimate third = statistics(25, NaN, 0.6, NaN, fiveToTen);
        PlanNodeStatsEstimate fourth = statistics(20, NaN, 0.6, NaN, threeToSeven);

        // no histogram on unknown
        assertEquals(calculator.addStatsAndCollapseDistinctValues(unknownRowCount, unknownRowCount).getVariableStatistics(VARIABLE).getHistogram(), Optional.empty());

        // check when rows are available histograms are added properly.
        ConnectorHistogram addedSameRange = DisjointRangeDomainHistogram.addDisjunction(unknownNullsFraction.getVariableStatistics(VARIABLE).getHistogram().get(), zeroToTen.toPrestoRange());
        assertAddStatsHistogram(unknownNullsFraction, unknownNullsFraction, calculator::addStatsAndSumDistinctValues, addedSameRange);
        assertAddStatsHistogram(unknownNullsFraction, unknownNullsFraction, calculator::addStatsAndCollapseDistinctValues, addedSameRange);
        assertAddStatsHistogram(unknownNullsFraction, unknownNullsFraction, calculator::addStatsAndMaxDistinctValues, addedSameRange);
        assertAddStatsHistogram(unknownNullsFraction, unknownNullsFraction, calculator::addStatsAndIntersect, addedSameRange);

        // check when only a sub-range is added, that the histogram still represents the full range
        ConnectorHistogram fullRangeFirst = DisjointRangeDomainHistogram.addDisjunction(first.getVariableStatistics(VARIABLE).getHistogram().get(), zeroToTen.toPrestoRange());
        ConnectorHistogram intersectedRangeSecond = DisjointRangeDomainHistogram.addConjunction(first.getVariableStatistics(VARIABLE).getHistogram().get(), zeroToFive.toPrestoRange());
        assertAddStatsHistogram(first, second, calculator::addStatsAndSumDistinctValues, fullRangeFirst);
        assertAddStatsHistogram(first, second, calculator::addStatsAndCollapseDistinctValues, fullRangeFirst);
        assertAddStatsHistogram(first, second, calculator::addStatsAndMaxDistinctValues, fullRangeFirst);
        assertAddStatsHistogram(first, second, calculator::addStatsAndIntersect, intersectedRangeSecond);

        // check when two ranges overlap, the new stats span both ranges
        ConnectorHistogram fullRangeSecondThird = DisjointRangeDomainHistogram.addDisjunction(second.getVariableStatistics(VARIABLE).getHistogram().get(), fiveToTen.toPrestoRange());
        ConnectorHistogram intersectedRangeSecondThird = DisjointRangeDomainHistogram.addConjunction(second.getVariableStatistics(VARIABLE).getHistogram().get(), fiveToTen.toPrestoRange());
        assertAddStatsHistogram(second, third, calculator::addStatsAndSumDistinctValues, fullRangeSecondThird);
        assertAddStatsHistogram(second, third, calculator::addStatsAndCollapseDistinctValues, fullRangeSecondThird);
        assertAddStatsHistogram(second, third, calculator::addStatsAndMaxDistinctValues, fullRangeSecondThird);
        assertAddStatsHistogram(second, third, calculator::addStatsAndIntersect, intersectedRangeSecondThird);

        // check when two ranges partially overlap, the addition/intersection is applied correctly
        ConnectorHistogram fullRangeThirdFourth = DisjointRangeDomainHistogram.addDisjunction(third.getVariableStatistics(VARIABLE).getHistogram().get(), threeToSeven.toPrestoRange());
        ConnectorHistogram intersectedRangeThirdFourth = DisjointRangeDomainHistogram.addConjunction(third.getVariableStatistics(VARIABLE).getHistogram().get(), threeToSeven.toPrestoRange());
        assertAddStatsHistogram(third, fourth, calculator::addStatsAndSumDistinctValues, fullRangeThirdFourth);
        assertAddStatsHistogram(third, fourth, calculator::addStatsAndCollapseDistinctValues, fullRangeThirdFourth);
        assertAddStatsHistogram(third, fourth, calculator::addStatsAndMaxDistinctValues, fullRangeThirdFourth);
        assertAddStatsHistogram(third, fourth, calculator::addStatsAndIntersect, intersectedRangeThirdFourth);
    }

    private static void assertAddStatsHistogram(PlanNodeStatsEstimate first, PlanNodeStatsEstimate second, BiFunction<PlanNodeStatsEstimate, PlanNodeStatsEstimate, PlanNodeStatsEstimate> function, ConnectorHistogram expected)
    {
        assertEquals(function.apply(first, second).getVariableStatistics(VARIABLE).getHistogram().get(), expected);
    }

    private static PlanNodeStatsEstimate statistics(double rowCount, double totalSize, double nullsFraction, double averageRowSize, StatisticRange range)
    {
        return PlanNodeStatsEstimate.builder()
                .setOutputRowCount(rowCount)
                .setTotalSize(totalSize)
                .addVariableStatistics(VARIABLE, VariableStatsEstimate.builder()
                        .setNullsFraction(nullsFraction)
                        .setAverageRowSize(averageRowSize)
                        .setStatisticsRange(range)
                        .setHistogram(Optional.of(DisjointRangeDomainHistogram.addConjunction(new UniformDistributionHistogram(range.getLow(), range.getHigh()), range.toPrestoRange())))
                        .build())
                .build();
    }

    private static StatisticRange openRange(double distinctValues)
    {
        return new StatisticRange(NEGATIVE_INFINITY, POSITIVE_INFINITY, distinctValues);
    }
}
