package de.fraunhofer.iosb.ilt.stp.processors;

import com.google.common.collect.ComparisonChain;
import com.google.gson.JsonElement;
import de.fraunhofer.iosb.ilt.configurable.ConfigEditor;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorBoolean;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorClass;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorInt;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorMap;
import de.fraunhofer.iosb.ilt.configurable.editor.EditorString;
import de.fraunhofer.iosb.ilt.sta.ServiceFailureException;
import de.fraunhofer.iosb.ilt.sta.dao.BaseDao;
import de.fraunhofer.iosb.ilt.sta.jackson.ObjectMapperFactory;
import de.fraunhofer.iosb.ilt.sta.model.Datastream;
import de.fraunhofer.iosb.ilt.sta.model.EntityType;
import de.fraunhofer.iosb.ilt.sta.model.Id;
import de.fraunhofer.iosb.ilt.sta.model.MultiDatastream;
import de.fraunhofer.iosb.ilt.sta.model.Observation;
import de.fraunhofer.iosb.ilt.sta.model.Thing;
import de.fraunhofer.iosb.ilt.sta.model.TimeObject;
import de.fraunhofer.iosb.ilt.sta.model.ext.EntityList;
import de.fraunhofer.iosb.ilt.sta.service.SensorThingsService;
import de.fraunhofer.iosb.ilt.stp.ProcessException;
import de.fraunhofer.iosb.ilt.stp.Processor;
import de.fraunhofer.iosb.ilt.stp.ProcessorHelper;
import de.fraunhofer.iosb.ilt.stp.sta.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.extra.Interval;

/**
 *
 * @author scf
 */
public class ProcessorBatchAggregate implements Processor {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorBatchAggregate.class);
    private static final String KEY_AGGREGATE_FOR = "aggregateFor";
    private static final String LB = Pattern.quote("[");
    private static final String RB = Pattern.quote("]");
    private static final Pattern POSTFIX_PATTERN = Pattern.compile("(.+)" + LB + "([0-9]+ [a-zA-Z]+)" + RB);
    private static final int RECEIVE_QUEUE_CAPACITY = 100000;

    private static class AggregationLevel implements Comparable<AggregationLevel> {

        public TemporalUnit unit;
        public int amount;
        public Duration duration;

        public static AggregationLevel of(String postfix) {
            postfix = postfix.trim();
            if (postfix.startsWith("[")) {
                postfix = postfix.substring(1);
            }
            if (postfix.endsWith("]")) {
                postfix = postfix.substring(0, postfix.length() - 1);
            }
            String[] split = postfix.split(" ");
            if (split.length != 2) {
                return null;
            }
            int amount = Integer.parseInt(split[0]);
            ChronoUnit unit = getUnit(split[1]);
            if (unit == null) {
                return null;
            }
            return new AggregationLevel(unit, amount);
        }

        public static boolean isPostfix(String postfix) {
            return of(postfix) != null;
        }

        public static boolean nameHasPostfix(String dsName) {
            return POSTFIX_PATTERN.matcher(dsName).matches();
        }

        private static ChronoUnit getUnit(String unitString) {
            unitString = unitString.toUpperCase();
            try {
                ChronoUnit unit = ChronoUnit.valueOf(unitString);
                return unit;
            } catch (IllegalArgumentException exc) {
            }
            try {
                ChronoUnit unit = ChronoUnit.valueOf(unitString + "S");
                return unit;
            } catch (IllegalArgumentException exc) {
            }
            return null;
        }

        public AggregationLevel(TemporalUnit unit, int amount) {
            this.unit = unit;
            this.amount = amount;
            this.duration = unit.getDuration().multipliedBy(amount);
        }

        public ZonedDateTime toIntervalStart(ZonedDateTime time) {
            ZonedDateTime start;
            switch (ChronoUnit.valueOf(unit.toString().toUpperCase())) {
                case SECONDS:
                    start = time.truncatedTo(ChronoUnit.MINUTES);
                    break;
                case MINUTES:
                    start = time.truncatedTo(ChronoUnit.HOURS);
                    break;
                case HOURS:
                    start = time.truncatedTo(ChronoUnit.DAYS);
                    break;
                case DAYS:
                    start = time.with(TemporalAdjusters.firstDayOfYear()).truncatedTo(ChronoUnit.DAYS);
                    break;
                default:
                    start = time.with(TemporalAdjusters.firstDayOfYear()).truncatedTo(ChronoUnit.DAYS);
            }
            long haveMillis = Duration.between(start, time).toMillis();
            long maxMillis = duration.toMillis();
            long periods = haveMillis / maxMillis;
            start = start.plus(duration.multipliedBy(periods));
            return start;
        }

        @Override
        public boolean equals(Object obj) {
            if (super.equals(obj)) {
                return true;
            }
            if (!(obj instanceof AggregationLevel)) {
                return false;
            }
            AggregationLevel otherLevel = (AggregationLevel) obj;
            return duration.equals(otherLevel.duration);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 53 * hash + Objects.hashCode(this.duration);
            return hash;
        }

        @Override
        public int compareTo(AggregationLevel o) {
            return ComparisonChain.start()
                    .compare(duration, o.duration)
                    .result();
        }

        @Override
        public String toString() {
            return amount + " " + unit;
        }

    }

    private static class AggregateCombo implements Comparable<AggregateCombo> {

        public final Thing targetThing;
        public final MultiDatastream target;
        public Datastream sourceDs;
        public MultiDatastream sourceMds;
        public boolean sourceIsAggregate;
        /**
         * Indicates that observations in the source contain a list of values.
         */
        public boolean sourceIsCollection = false;
        public AggregationLevel level;
        public String baseName;
        private ZoneId zoneId;
        private Interval currentInterval;

        public AggregateCombo(Thing targetThing, MultiDatastream target) {
            this.targetThing = targetThing;
            this.target = target;
        }

        public boolean hasSource() {
            return sourceDs != null || sourceMds != null;
        }

        public EntityType getSourceType() {
            if (sourceDs != null) {
                return EntityType.DATASTREAM;
            }
            if (sourceMds != null) {
                return EntityType.MULTIDATASTREAM;
            }
            return null;
        }

        public Id getSourceId() {
            if (sourceDs != null) {
                return sourceDs.getId();
            }
            if (sourceMds != null) {
                return sourceMds.getId();
            }
            return null;
        }

        public BaseDao<Observation> getObsDaoForSource() {
            if (sourceDs != null) {
                return sourceDs.observations();
            }
            if (sourceMds != null) {
                return sourceMds.observations();
            }
            return null;
        }

        public Observation getLastForTarget() {
            try {
                return target.observations().query().select("id", "phenomenonTime").orderBy("phenomenonTime desc").first();
            } catch (ServiceFailureException ex) {
                LOGGER.error("Error fetching last observation.", ex);
                return null;
            }
        }

        public Observation getFirstForSource() {
            try {
                if (hasSource()) {
                    return getObsDaoForSource().query().select("id", "phenomenonTime").orderBy("phenomenonTime asc").first();
                }
                return null;
            } catch (ServiceFailureException ex) {
                LOGGER.error("Error fetching first observation.", ex);
                return null;
            }
        }

        public Observation getLastForSource() {
            try {
                if (hasSource()) {
                    return getObsDaoForSource().query().select("id", "phenomenonTime").orderBy("phenomenonTime desc").first();
                }
                return null;
            } catch (ServiceFailureException ex) {
                LOGGER.error("Error fetching last observation.", ex);
                return null;
            }
        }

        public List<Observation> getObservationsForSource(Instant start, Instant end) {
            List<Observation> result = new ArrayList<>();
            if (hasSource()) {
                try {
                    StringBuilder filter = new StringBuilder();
                    filter.append("overlaps(phenomenonTime,")
                            .append(start.toString())
                            .append("/")
                            .append(end.toString())
                            .append(")");
                    EntityList<Observation> entityList = getObsDaoForSource()
                            .query()
                            .filter(filter.toString())
                            .orderBy("phenomenonTime asc")
                            .top(1000)
                            .list();
                    for (Iterator<Observation> it = entityList.fullIterator(); it.hasNext();) {
                        Observation entity = it.next();
                        result.add(entity);
                    }
                } catch (ServiceFailureException ex) {
                    LOGGER.error("Failed to fetch observations.", ex);
                }
            }
            return result;
        }

        public void resolveZoneId(ZoneId dflt) {
            if (zoneId == null) {
                Map<String, Object> properties = targetThing.getProperties();
                Object zoneName = properties.get("timeZone");
                if (zoneName == null || zoneName.toString().isEmpty()) {
                    zoneId = dflt;
                } else {
                    try {
                        zoneId = ZoneId.of(zoneName.toString());
                    } catch (DateTimeException ex) {
                        LOGGER.warn("Invalid zone: " + zoneName, ex);
                        zoneId = dflt;
                    }
                }
            }
        }

        public ZoneId getZoneId() {
            return zoneId;
        }

        public String getSourceObsMqttPath() {
            if (sourceDs != null) {
                return "v1.0/Datastreams(" + sourceDs.getId() + ")/Observations?$select=id,phenomenonTime";
            }
            if (sourceMds != null) {
                return "v1.0/MultiDatastreams(" + sourceMds.getId() + ")/Observations?$select=id,phenomenonTime";
            }
            return "";
        }

        public List<Interval> calculateIntervalsForTime(TimeObject phenTime) {
            List<Interval> retval = new ArrayList<>();
            Instant phenTimeStart = getPhenTimeStart(phenTime);
            Instant phenTimeEnd = getPhenTimeEnd(phenTime);

            ZonedDateTime atZone = phenTimeStart.atZone(getZoneId());
            ZonedDateTime intStart = level.toIntervalStart(atZone);
            ZonedDateTime intEnd = intStart.plus(level.amount, level.unit);

            retval.add(Interval.of(intStart.toInstant(), intEnd.toInstant()));
            while (intEnd.toInstant().isBefore(phenTimeEnd)) {
                intStart = intEnd;
                intEnd = intStart.plus(level.amount, level.unit);
                retval.add(Interval.of(intStart.toInstant(), intEnd.toInstant()));
            }
            return retval;
        }

        /**
         * Checks if the given interval is the same as the current interval. If
         * they are not the same, the current interval is updated.
         *
         * @param other The interval to check against the current interval and
         * to replace the current interval with if they are not the same.
         * @return null if the given interval is the same as the current
         * interval, otherwise the current interval.
         */
        public Interval replaceIfNotCurrent(Interval other) {
            if (currentInterval == null) {
                // There is no interval yet. This happens the first time at startup.
                currentInterval = other;
                return null;
            }
            if (currentInterval.equals(other)) {
                // The given interval is the same. Do nothing.
                return null;
            } else {
                // The interval changed. Recalculate the old interval.
                Interval old = currentInterval;
                currentInterval = other;
                return old;
            }
        }

        /**
         * Unsets the current interval. If the given interval is the same as the
         * current interval, null is returned. If the given interval is not the
         * same as the current interval, the current interval is returned.
         *
         * @param other The interval to check against the current interval.
         * @return null if the given interval is the same as the current
         * interval.
         */
        public Interval unsetCurrent(Interval other) {
            if (currentInterval == null) {
                // There is no interval.
                return null;
            }
            if (currentInterval.equals(other)) {
                // The given interval is the same. Do nothing.
                currentInterval = null;
                return null;
            } else {
                // The interval is different. Recalculate the old interval.
                Interval old = currentInterval;
                currentInterval = null;
                return old;
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (super.equals(obj)) {
                return true;
            }
            if (!(obj instanceof AggregateCombo)) {
                return false;
            }
            AggregateCombo otherCombo = (AggregateCombo) obj;
            if (!level.equals(otherCombo.level)) {
                return false;
            }
            if (!baseName.equals(otherCombo.baseName)) {
                return false;
            }
            return target.getId().equals(otherCombo.target.getId());
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 73 * hash + Objects.hashCode(this.target);
            hash = 73 * hash + Objects.hashCode(this.level);
            hash = 73 * hash + Objects.hashCode(this.baseName);
            return hash;
        }

        @Override
        public int compareTo(AggregateCombo o) {
            return ComparisonChain.start()
                    .compare(level, o.level)
                    .compare(baseName, o.baseName)
                    .result();
        }

        @Override
        public String toString() {
            if (sourceDs != null) {
                return baseName + " " + level + ". (d " + sourceDs.getId() + " -> md " + target.getId() + ")";
            }
            if (sourceMds != null) {
                return baseName + " " + level + ". (md " + sourceMds.getId() + " -> md " + target.getId() + ")";
            }
            return baseName + " " + level + ". (? -> md " + target.getId() + ")";
        }

    }

    private static class MessageContext {

        public final List<AggregateCombo> combos;
        public final String topic;
        public final String message;

        public MessageContext(List<AggregateCombo> combos, String topic, String message) {
            this.combos = combos;
            this.topic = topic;
            this.message = message;
        }
    }

    private class CalculationOrder implements Delayed {

        private AtomicBoolean waiting = new AtomicBoolean(true);
        private final AggregateCombo combo;
        private final Interval interval;
        private final Instant targetTime;
        private final long targetMillis;

        public CalculationOrder(AggregateCombo combo, Interval interval, Instant delayUntill) {
            this.combo = combo;
            this.interval = interval;
            this.targetTime = delayUntill;
            this.targetMillis = targetTime.toEpochMilli();
        }

        public void execute() {
            waiting.set(false);
            orders.remove(this);
            try {
                calculateAggregate(combo, interval);
            } catch (ServiceFailureException | ProcessException ex) {
                LOGGER.error("Failed to calculate order!", ex);
            }
        }

        public Instant getTargetTime() {
            return targetTime;
        }

        @Override
        public boolean equals(Object obj) {
            if (super.equals(obj)) {
                return true;
            }
            if (!(obj instanceof CalculationOrder)) {
                return false;
            }
            CalculationOrder otherOrder = (CalculationOrder) obj;
            if (waiting.get() != otherOrder.waiting.get()) {
                return false;
            }
            if (!interval.equals(otherOrder.interval)) {
                return false;
            }
            return combo.compareTo(otherOrder.combo) == 0;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 23 * hash + (this.waiting.get() ? 1 : 0);
            hash = 23 * hash + Objects.hashCode(this.combo);
            hash = 23 * hash + Objects.hashCode(this.interval);
            return hash;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(targetMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            CalculationOrder other = (CalculationOrder) o;
            return ComparisonChain.start()
                    .compare(targetMillis, other.targetMillis)
                    .compareTrueFirst(waiting.get(), other.waiting.get())
                    .compare(combo, other.combo)
                    .compare(interval.getStart(), other.interval.getStart())
                    .result();
        }

    }

    private EditorMap<Map<String, Object>> editor;
    private EditorClass<SensorThingsService, Object, Service> editorServiceSource;
    private EditorClass<SensorThingsService, Object, Service> editorServiceTarget;
    private EditorString editorTimeZone;
    private EditorInt editorDelay;
    private EditorInt editorThreadCount;
    private EditorBoolean editorFixReferences;

    private boolean noAct = false;
    private ZoneId zoneId;
    private SensorThingsService stsSource;
    private Service sourceService;
    private SensorThingsService stsTarget;
    private Service targetService;
    private Duration orderDelay;
    private boolean fixReferences;
    private boolean sourceEqualsTarget;

    private MqttClient mqttClient;
    private Map<String, List<AggregateCombo>> comboBySource;
    private BlockingQueue<MessageContext> messagesToHandle = new LinkedBlockingQueue<>(RECEIVE_QUEUE_CAPACITY);
    private AtomicInteger messagesCount = new AtomicInteger(0);
    private Set<CalculationOrder> orders = new HashSet<>();
    private DelayQueue<CalculationOrder> orderQueue = new DelayQueue<>();
    private ExecutorService orderExecutorService;
    private ExecutorService messageReceptionService;
    private Aggregator aggregator = new Aggregator();

    @Override
    public void configure(JsonElement config, Void context, Void edtCtx) {
        stsSource = new SensorThingsService();
        stsTarget = new SensorThingsService();
        getConfigEditor(context, edtCtx).setConfig(config);
        sourceService = editorServiceSource.getValue();
        targetService = editorServiceTarget.getValue();
        zoneId = ZoneId.of(editorTimeZone.getValue());
        sourceService.setNoAct(noAct);
        targetService.setNoAct(noAct);
        orderDelay = Duration.ofMillis(editorDelay.getValue().longValue());
        fixReferences = editorFixReferences.getValue();
        sourceEqualsTarget = sourceService.getService().getEndpoint().equals(targetService.getService().getEndpoint());
    }

    @Override
    public ConfigEditor<?> getConfigEditor(Void context, Void edtCtx) {
        if (editor == null) {
            editor = new EditorMap<>();

            editorServiceSource = new EditorClass<>(stsSource, null, Service.class, "Source Service", "The service to read observations from.");
            editor.addOption("source", editorServiceSource, false);

            editorServiceTarget = new EditorClass<>(stsTarget, null, Service.class, "Target Service", "The service to write aggregate observations to.");
            editor.addOption("target", editorServiceTarget, false);

            editorTimeZone = new EditorString("Europe/Amsterdam", 1, "TimeZone", "The timezone to use when determining the start of the day,hour,etc.");
            editor.addOption("timeZone", editorTimeZone, true);

            editorDelay = new EditorInt(0, 999999, 1, 10000, "Delay", "The number of milliseconds to delay calculations with, in order to avoid duplicate calculations.");
            editor.addOption("delay", editorDelay, true);

            editorThreadCount = new EditorInt(0, 10, 1, 2, "Thread Count", "The number of simultanious calculations to run in parallel.");
            editor.addOption("threads", editorThreadCount, true);

            editorFixReferences = new EditorBoolean(false, "Fix References", "Fix the references between aggregate multidatastreams.");
            editor.addOption("fixRefs", editorFixReferences, true);
        }
        return editor;
    }

    @Override
    public void setNoAct(boolean noAct) {
        this.noAct = noAct;
        if (sourceService != null) {
            sourceService.setNoAct(noAct);
        }
        if (targetService != null) {
            targetService.setNoAct(noAct);
        }
    }

    private Map<String, List<AggregateCombo>> findTargetMultiDatastreams() {
        Map<String, List<AggregateCombo>> result = new HashMap<>();
        try {
            Iterator<Thing> things = targetService.getAllThings();
            while (things.hasNext()) {
                Thing thing = things.next();
                EntityList<MultiDatastream> dsList = thing.multiDatastreams().query().filter("endsWith(name, ']')").list();
                for (Iterator<MultiDatastream> it = dsList.fullIterator(); it.hasNext();) {
                    MultiDatastream mds = it.next();
                    String name = mds.getName();
                    Matcher matcher = POSTFIX_PATTERN.matcher(name);
                    if (!matcher.matches()) {
                        LOGGER.debug("MultiDatastream {} is not an aggregate.");
                        continue;
                    }
                    AggregateCombo combo = new AggregateCombo(thing, mds);
                    combo.baseName = matcher.group(1).trim();
                    String postfix = matcher.group(2);
                    combo.level = AggregationLevel.of(postfix);
                    if (combo.level == null) {
                        LOGGER.debug("Not a postfix: {}.", postfix);
                        continue;
                    }
                    combo.resolveZoneId(zoneId);
                    LOGGER.debug("Found: {} from {}, timeZone {}", combo.level, combo.target.getName(), combo.getZoneId());
                    List<AggregateCombo> list = result.get(combo.baseName);
                    if (list == null) {
                        list = new ArrayList<>();
                        result.put(combo.baseName, list);
                    }
                    list.add(combo);
                }
            }
        } catch (ServiceFailureException exc) {
            LOGGER.error("Service error: ", exc);
        }
        for (List<AggregateCombo> list : result.values()) {
            Collections.sort(list);
        }
        return result;
    }

    private void findSourceDatastreams(AggregateCombo target) {
        try {

            String nameQuoted = "'" + target.baseName.replaceAll("'", "''") + "'";
            {
                List<Datastream> list = stsSource.datastreams().query().filter("name eq " + nameQuoted).list().toList();
                if (list.size() > 1) {
                    LOGGER.warn("Multiple ({}) sources found for {}.", list.size(), target.baseName);
                }
                if (list.size() > 0) {
                    target.sourceDs = list.get(0);
                    target.sourceIsAggregate = false;
                    checkReference(target.sourceDs, target.target);
                    return;
                }
            }
            {
                List<MultiDatastream> list = stsSource.multiDatastreams().query().filter("name eq " + nameQuoted).list().toList();
                if (list.size() > 1) {
                    LOGGER.warn("Multiple ({}) sources found for {}.", list.size(), target.baseName);
                }
                if (list.size() > 0) {
                    target.sourceMds = list.get(0);
                    target.sourceIsAggregate = false;
                    checkReference(target.sourceMds, target.target);
                    return;
                }
            }
            {
                List<Datastream> list = stsSource.datastreams().query().filter("startswith(name," + nameQuoted + ")").list().toList();
                if (list.size() > 1) {
                    LOGGER.warn("Multiple ({}) sources found for {}.", list.size(), target.baseName);
                }
                for (Datastream source : list) {
                    String postfix = source.getName().substring(target.baseName.length());
                    if (!AggregationLevel.isPostfix(postfix)) {
                        continue;
                    }
                    target.sourceDs = source;
                    target.sourceIsAggregate = false;
                    target.sourceIsCollection = true;
                    checkReference(target.sourceDs, target.target);
                    return;
                }
            }
            LOGGER.warn("No source found for {}.", target.baseName);
        } catch (ServiceFailureException ex) {
            LOGGER.error("Failed to find source for {}." + target.baseName);
            LOGGER.debug("Exception:", ex);
        }
    }

    private void checkReference(Datastream source, MultiDatastream aggregate) {
        String expectedAggFor;
        if (sourceEqualsTarget) {
            expectedAggFor = "/Datastreams(" + source.getId().getUrl() + ")";
        } else {
            expectedAggFor = source.getSelfLink().toString();
        }
        checkReference(aggregate, expectedAggFor);
    }

    private void checkReference(MultiDatastream source, MultiDatastream aggregate) {
        String expectedAggFor;
        if (sourceEqualsTarget) {
            expectedAggFor = "/MultiDatastreams(" + source.getId().getUrl() + ")";
        } else {
            expectedAggFor = source.getSelfLink().toString();
        }
        checkReference(aggregate, expectedAggFor);
    }

    private void checkReference(MultiDatastream aggregate, String expectedAggFor) {
        Map<String, Object> properties = aggregate.getProperties();
        if (properties == null) {
            properties = new HashMap<>();
            aggregate.setProperties(properties);
        }
        String aggFor = Objects.toString(properties.get(KEY_AGGREGATE_FOR));
        if (!expectedAggFor.equals(aggFor)) {
            if (fixReferences) {
                try {
                    LOGGER.info("Setting source reference for {} to {}.", aggregate.getName(), expectedAggFor);
                    properties.put(KEY_AGGREGATE_FOR, expectedAggFor);
                    targetService.getService().update(aggregate);
                } catch (ServiceFailureException ex) {
                    LOGGER.error("Failed to update reference.", ex);
                }
            } else {
                LOGGER.info("Source reference for {} not correct. Should be {}.", aggregate.getName(), expectedAggFor);
            }
        }
    }

    private void findSourceDatastreams(List<AggregateCombo> targets) {
        int i = 0;
        for (AggregateCombo target : targets) {
            int idx = i;
            boolean found = false;
            while (!found && idx > 0) {
                // check the other combos
                idx--;
                AggregateCombo test = targets.get(idx);
                long smaller = test.level.duration.getSeconds();
                long larger = target.level.duration.getSeconds();
                if (larger % smaller == 0) {
                    LOGGER.debug("{}: {} ~ {} ({})", target.baseName, target.level, test.level, (larger / smaller));
                    target.sourceMds = test.target;
                    target.sourceIsAggregate = true;
                    found = true;
                    checkReference(target.sourceMds, target.target);
                }
            }
            if (!found) {
                // No other combo is valid.
                findSourceDatastreams(target);
            }
            i++;
            LOGGER.debug("Found source for: {}.", target);
        }
    }

    private void findSourceDatastreams(Map<String, List<AggregateCombo>> targets) {
        for (List<AggregateCombo> target : targets.values()) {
            findSourceDatastreams(target);
        }
    }

    private void calculateAggregate(AggregateCombo combo, Interval interval) throws ServiceFailureException, ProcessException {
        Instant start = interval.getStart();
        Instant end = interval.getEnd();
        List<Observation> sourceObs = combo.getObservationsForSource(start, end);
        LOGGER.info("Calculating {} using {} obs for {}.", interval, sourceObs.size(), combo);
        if (sourceObs.isEmpty()) {
            return;
        }
        LOGGER.debug("Obs:        {}/{}.", sourceObs.get(0).getPhenomenonTime(), sourceObs.get(sourceObs.size() - 1).getPhenomenonTime());

        BigDecimal[] result;
        try {
            if (combo.sourceIsAggregate) {
                result = aggregator.calculateAggregateResultFromAggregates(sourceObs);
            } else if (combo.sourceIsCollection) {
                result = aggregator.calculateAggregateResultFromOriginalLists(interval, sourceObs);
            } else {
                result = aggregator.calculateAggregateResultFromOriginals(interval, sourceObs);
            }
        } catch (NumberFormatException exc) {
            LOGGER.error("Failed to calculate statistics for " + combo.toString() + " interval " + interval, exc);
            return;
        }
        Observation newObs = new Observation(result, combo.target);
        Map<String, Object> parameters = new HashMap<>();
        for (Observation sourceOb : sourceObs) {
            Map<String, Object> otherParams = sourceOb.getParameters();
            if (otherParams == null) {
                continue;
            }
            parameters.putAll(otherParams);
        }
        parameters.put("resultCount", sourceObs.size());
        newObs.setParameters(parameters);
        newObs.setPhenomenonTimeFrom(interval);
        targetService.addObservation(newObs);
    }

    private void calculateAggregates(AggregateCombo combo) throws ServiceFailureException, ProcessException {
        Observation lastAggObs = combo.getLastForTarget();

        Instant calcIntervalStart;
        if (lastAggObs == null) {
            Observation firstSourceObs = combo.getFirstForSource();
            if (firstSourceObs == null) {
                LOGGER.debug("No source observations at all for {}.", combo);
                return;
            }
            Instant firstSourceStart = getPhenTimeStart(firstSourceObs);

            ZonedDateTime atZone = firstSourceStart.atZone(combo.getZoneId());
            ZonedDateTime firstIntStart = combo.level.toIntervalStart(atZone);
            if (atZone.isEqual(firstIntStart)) {
                calcIntervalStart = firstIntStart.toInstant();
            } else {
                calcIntervalStart = firstIntStart.plus(combo.level.duration).toInstant();
            }

        } else {
            TimeObject lastAggPhenTime = lastAggObs.getPhenomenonTime();
            calcIntervalStart = lastAggPhenTime.getAsInterval().getEnd();
        }
        Observation lastSourceObs = combo.getLastForSource();
        if (lastSourceObs == null) {
            LOGGER.debug("No source observations at all for {}.", combo);
            return;
        }
        Instant lastSourcePhenTime = getPhenTimeEnd(lastSourceObs);

        boolean more = true;
        while (more) {
            Instant calcIntervalEnd = calcIntervalStart.plus(combo.level.duration);

            if (lastSourcePhenTime.isBefore(calcIntervalEnd)) {
                LOGGER.info("Nothing (more) to do for {}.", combo);
                return;
            }

            calculateAggregate(combo, Interval.of(calcIntervalStart, calcIntervalEnd));
            calcIntervalStart = calcIntervalEnd;
        }

    }

    private void calculateAggregates(List<AggregateCombo> targets) {
        for (AggregateCombo target : targets) {
            try {
                calculateAggregates(target);
            } catch (ServiceFailureException | ProcessException ex) {
                LOGGER.error("Error calculating for: " + target, ex);
            }
        }
    }

    private void calculateAggregates(Map<String, List<AggregateCombo>> targets) {
        for (List<AggregateCombo> target : targets.values()) {
            calculateAggregates(target);
        }
    }

    private void createSubscriptions(Map<String, List<AggregateCombo>> mdsMap) throws MqttException {
        comboBySource = new HashMap<>();
        for (List<AggregateCombo> list : mdsMap.values()) {
            for (AggregateCombo combo : list) {
                String path = combo.getSourceObsMqttPath();
                if (path.isEmpty()) {
                    continue;
                }
                List<AggregateCombo> bySource = comboBySource.get(path);
                if (bySource == null) {
                    bySource = new ArrayList<>();
                    comboBySource.put(path, bySource);
                }
                bySource.add(combo);
            }
        }
        LOGGER.info("Found {} mqtt paths to watch.", comboBySource.keySet().size());

        for (Map.Entry<String, List<AggregateCombo>> entry : comboBySource.entrySet()) {
            String path = entry.getKey();
            LOGGER.debug("Subscribing to: {}", path);
            final List<AggregateCombo> combos = entry.getValue();
            mqttClient.subscribe(path, (String topic, MqttMessage message) -> {
                if (messagesToHandle.offer(new MessageContext(combos, topic, message.toString()))) {
                    int count = messagesCount.getAndIncrement();
                    if (count > 1) {
                        LOGGER.trace("Receive queue size: {}", count);
                    }
                } else {
                    LOGGER.error("Receive queue is full! More than {} messages in backlog", RECEIVE_QUEUE_CAPACITY);
                }
            });
        }
    }

    private void createOrderFor(List<AggregateCombo> combos, String message) {
        try {
            AggregateCombo mainCombo = combos.get(0);
            Id sourceId = mainCombo.getSourceId();
            EntityType sourceType = mainCombo.getSourceType();
            Observation obs = parseMessageToObservation(message);
            for (AggregateCombo combo : combos) {
                createOrdersFor(combo, obs, sourceType, sourceId);
            }
        } catch (IOException ex) {
            LOGGER.error("Invalid message.", ex);
        } catch (Exception ex) {
            LOGGER.error("Exception processing!", ex);
        }
    }

    private void createOrdersFor(AggregateCombo combo, Observation obs, EntityType sourceType, Id sourceId) {
        List<Interval> intervals = combo.calculateIntervalsForTime(obs.getPhenomenonTime());
        int count = intervals.size();
        if (count > 1) {
            for (Interval interval : intervals) {
                LOGGER.debug("{} {}: Interval {} recalculating.", sourceType, sourceId, interval);
                CalculationOrder order = new CalculationOrder(combo, interval, Instant.now().plus(orderDelay));
                offerOrder(order);
            }
        } else {
            for (Interval interval : intervals) {
                Interval toCalculate;
                if (interval.getEnd().equals(getPhenTimeEnd(obs))) {
                    // The observation is the last one for the interval.
                    LOGGER.debug("{} {}: Interval {} recalculating, because end reached.", sourceType, sourceId, interval);
                    CalculationOrder order = new CalculationOrder(combo, interval, Instant.now().plus(orderDelay));
                    offerOrder(order);
                    toCalculate = combo.unsetCurrent(interval);
                } else {
                    toCalculate = combo.replaceIfNotCurrent(interval);
                }
                if (toCalculate != null) {
                    LOGGER.debug("{} {}: Interval {} recalculating, because we now have {}.", sourceType, sourceId, toCalculate, interval);
                    CalculationOrder order = new CalculationOrder(combo, toCalculate, Instant.now().plus(orderDelay));
                    offerOrder(order);
                }
            }
        }
    }

    private boolean offerOrder(CalculationOrder order) {
        if (orders.contains(order)) {
            return false;
        }
        if (!orderQueue.offer(order)) {
            LOGGER.error("Could not queue order, queue full!");
            return false;
        }
        orders.add(order);
        return true;
    }

    private Observation parseMessageToObservation(String message) throws IOException {
        return ObjectMapperFactory.get().readValue(message, Observation.class);
    }

    @Override
    public void process() {
        // Find target multidatastreams
        Map<String, List<AggregateCombo>> mdsMap = findTargetMultiDatastreams();
        LOGGER.info("Found {} comboSets", mdsMap.size());
        // Find source datastreams matching the targets
        findSourceDatastreams(mdsMap);
        // Do the calculations
        calculateAggregates(mdsMap);
    }

    @Override
    public void startListening() {
        try {
            mqttClient = sourceService.getMqttClient();
            orderExecutorService = ProcessorHelper.createProcessors(
                    editorThreadCount.getValue(),
                    orderQueue,
                    x -> x.execute(),
                    "Aggregator");
            messageReceptionService = ProcessorHelper.createProcessors(editorThreadCount.getValue(),
                    messagesToHandle, (MessageContext x) -> {
                        messagesCount.decrementAndGet();
                        createOrderFor(x.combos, x.message);
                    },
                    "Receiver");
            // Find target multidatastreams
            Map<String, List<AggregateCombo>> mdsMap = findTargetMultiDatastreams();
            LOGGER.info("Found {} comboSets", mdsMap.size());
            // Find source datastreams matching the targets
            findSourceDatastreams(mdsMap);
            createSubscriptions(mdsMap);
        } catch (MqttException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    @Override
    public void stopListening() {
        try {
            if (orderExecutorService != null) {
                ProcessorHelper.shutdownProcessors(orderExecutorService, orderQueue, 5, TimeUnit.SECONDS);
            }
            if (mqttClient.isConnected()) {
                LOGGER.info("Stopping MQTT client.");
                String[] paths = comboBySource.keySet().toArray(new String[comboBySource.size()]);
                mqttClient.unsubscribe(paths);
                mqttClient.disconnect();
            } else {
                LOGGER.info("MQTT client already stopped.");
            }
        } catch (MqttException ex) {
            LOGGER.error("Problem while disconnecting!", ex);
        }
    }

    private static Instant getPhenTimeStart(Observation obs) {
        TimeObject phenTime = obs.getPhenomenonTime();
        return getPhenTimeStart(phenTime);
    }

    private static Instant getPhenTimeStart(TimeObject phenTime) {
        if (phenTime.isInterval()) {
            return phenTime.getAsInterval().getStart();
        }
        return phenTime.getAsDateTime().toInstant();
    }

    private static Instant getPhenTimeEnd(Observation obs) {
        TimeObject phenTime = obs.getPhenomenonTime();
        return getPhenTimeEnd(phenTime);
    }

    private static Instant getPhenTimeEnd(TimeObject phenTime) {
        if (phenTime.isInterval()) {
            return phenTime.getAsInterval().getEnd();
        }
        return phenTime.getAsDateTime().toInstant();
    }
}
