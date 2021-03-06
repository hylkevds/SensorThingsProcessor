/*
 * Copyright (C) 2018 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
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
package de.fraunhofer.iosb.ilt.stp.processors.aggregation;

import de.fraunhofer.iosb.ilt.sta.model.Observation;
import de.fraunhofer.iosb.ilt.sta.model.TimeObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.extra.Interval;

/**
 *
 * @author Hylke van der Schaaf
 */
public class Aggregator {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Aggregator.class);

    private int getScale(Number number) {
        if (number instanceof BigDecimal) {
            return ((BigDecimal) number).scale();
        } else if (number instanceof Integer || number instanceof Long) {
            return new BigDecimal(number.longValue()).scale();
        }
        return new BigDecimal(number.doubleValue()).scale();
    }

    private BigDecimal handleResult(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof BigDecimal) {
            return (BigDecimal) result;
        }
        if (result instanceof Double) {
            return new BigDecimal((Double) result);
        }
        if (result instanceof Long) {
            return new BigDecimal((Long) result);
        }
        if (result instanceof Integer) {
            return new BigDecimal((Integer) result);
        }
        if (result instanceof Number) {
            Number number = (Number) result;
            return new BigDecimal(number.doubleValue());
        } else if (result instanceof List) {
            List list = (List) result;
            if (list.isEmpty()) {
                return null;
            }
            return handleResult(list.get(0));
        } else {
            LOGGER.trace("Unknow result type: {}", result.getClass().getName());
        }
        return null;
    }

    private Instant handleTime(TimeObject phenTime) {
        if (phenTime.isInterval()) {
            Interval interval = phenTime.getAsInterval();
            return interval.getStart().plus(interval.toDuration().dividedBy(2));
        }
        return phenTime.getAsDateTime().toInstant();
    }

    public List<BigDecimal> calculateAggregateResultFromOriginalLists(Interval interval, List<Observation> sourceObs) {
        List<BigDecimal> result;
        int scale = 0;
        DescriptiveStatistics stats = new DescriptiveStatistics();
        BigDecimal min = new BigDecimal(Double.MAX_VALUE);
        BigDecimal max = new BigDecimal(-Double.MAX_VALUE);
        for (Observation obs : sourceObs) {
            Object obsResultObj = obs.getResult();
            if (!(obsResultObj instanceof List)) {
                LOGGER.error("Expected list result, got {}", obsResultObj == null ? obsResultObj : obsResultObj.getClass().getName());
                continue;
            }
            List list = (List) obsResultObj;

            TimeObject phenomenonTime = obs.getPhenomenonTime();
            if (!phenomenonTime.isInterval()) {
                LOGGER.error("Expected phenTime to be an interval.");
                continue;
            }
            Interval phenInterval = phenomenonTime.getAsInterval();
            int itemCount = list.size();
            int firstItem = 0;
            int lastItem = itemCount - 1;
            double itemDistMillis = ((double) phenInterval.toDuration().toMillis()) / itemCount;
            if (phenInterval.getStart().isBefore(interval.getStart())) {
                long skipMillis = Duration.between(phenInterval.getStart(), interval.getStart()).toMillis();
                firstItem = (int) (skipMillis / itemDistMillis);
            }
            if (phenInterval.getEnd().isAfter(interval.getEnd())) {
                long skipMillis = Duration.between(interval.getEnd(), phenInterval.getEnd()).toMillis();
                int skipEnd = (int) (skipMillis / itemDistMillis);
                lastItem -= skipEnd;
            }

            for (int i = firstItem; i <= lastItem && i < itemCount; i++) {
                BigDecimal number = handleResult(list.get(i));
                if (number == null) {
                    LOGGER.warn("Empty result in {}", obs);
                    continue;
                }
                scale = Math.max(getScale(number), scale);
                stats.addValue(number.doubleValue());
                min = number.compareTo(min) < 0 ? number : min;
                max = number.compareTo(max) > 0 ? number : max;
            }
        }
        BigDecimal avg = new BigDecimal(stats.getMean());
        BigDecimal dev = new BigDecimal(stats.getStandardDeviation());

        result = new ArrayList<>(4);
        result.add(avg.setScale(Math.min(scale, avg.scale()), RoundingMode.HALF_UP));
        result.add(min);
        result.add(max);
        result.add(dev.setScale(Math.min(scale, dev.scale()), RoundingMode.HALF_UP));
        return result;
    }

    public List<BigDecimal> calculateAggregateResultFromOriginals(Interval interval, List<Observation> sourceObs) {
        List<BigDecimal> result;
        int scale = 0;
        DescriptiveStatistics stats = new DescriptiveStatistics();
        Number prevResult = null;
        long prevMillis = 0;
        long curMillis = 0;
        long startMillis = interval.getStart().toEpochMilli();
        long endMillis = interval.getEnd().toEpochMilli();
        long totalMillis = endMillis - startMillis;
        double avg = 0;
        double curResult = 0;
        for (Observation obs : sourceObs) {

            Number number = handleResult(obs.getResult());
            if (number == null) {
                LOGGER.warn("Empty result in {}", obs);
                continue;
            }
            scale = Math.max(getScale(number), scale);

            curResult = handleResult(obs.getResult()).doubleValue();
            Instant curTime = handleTime(obs.getPhenomenonTime());
            curMillis = curTime.toEpochMilli();
            if (prevResult == null) {
                long deltaMillis = curMillis - startMillis;
                avg += curResult * deltaMillis / totalMillis;
            } else {
                long deltaMillis = curMillis - prevMillis;
                avg += 0.5 * (curResult + prevResult.doubleValue()) * deltaMillis / totalMillis;
            }
            prevMillis = curMillis;
            prevResult = curResult;
            stats.addValue(number.doubleValue());
        }
        long deltaMillis = endMillis - curMillis;
        avg += curResult * deltaMillis / totalMillis;

        BigDecimal average = BigDecimal.valueOf(avg).setScale(scale, RoundingMode.HALF_UP);
        result = new ArrayList<>(4);
        result.add(average);
        result.add(new BigDecimal(stats.getMin()).setScale(scale, RoundingMode.HALF_UP));
        result.add(new BigDecimal(stats.getMax()).setScale(scale, RoundingMode.HALF_UP));
        result.add(new BigDecimal(stats.getStandardDeviation()).setScale(scale, RoundingMode.HALF_UP));
        return result;
    }

    public List<BigDecimal> calculateAggregateResultFromAggregates(List<Observation> sourceObs) {
        List<BigDecimal> result;
        DescriptiveStatistics stats = new DescriptiveStatistics();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int scale = 0;
        for (Observation obs : sourceObs) {
            Object input = obs.getResult();
            if (input instanceof List) {
                List list = (List) input;
                Number number = handleResult(list.get(0));
                scale = Math.max(getScale(number), scale);

                stats.addValue(number.doubleValue());
                min = Math.min(min, handleResult(list.get(1)).doubleValue());
                max = Math.max(max, handleResult(list.get(2)).doubleValue());
            } else {
                String type = input == null ? "null" : input.getClass().getName();
                LOGGER.error("Aggregate input of obs {} should be a List, not a {}", obs.getId(), type);
                throw new IllegalArgumentException("Expected List, got " + type);
            }
        }
        result = new ArrayList<>(4);
        result.add(new BigDecimal(stats.getMean()).setScale(scale, RoundingMode.HALF_UP));
        result.add(new BigDecimal(min).setScale(scale, RoundingMode.HALF_UP));
        result.add(new BigDecimal(max).setScale(scale, RoundingMode.HALF_UP));
        result.add(new BigDecimal(stats.getStandardDeviation()).setScale(scale, RoundingMode.HALF_UP));

        return result;
    }
}
