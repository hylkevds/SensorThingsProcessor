package de.fraunhofer.iosb.ilt.stp.processors.aggregation;

import de.fraunhofer.iosb.ilt.sta.ServiceFailureException;
import de.fraunhofer.iosb.ilt.sta.model.Datastream;
import de.fraunhofer.iosb.ilt.sta.model.MultiDatastream;
import de.fraunhofer.iosb.ilt.sta.model.Thing;
import de.fraunhofer.iosb.ilt.sta.model.ext.EntityList;
import de.fraunhofer.iosb.ilt.sta.service.SensorThingsService;
import de.fraunhofer.iosb.ilt.stp.aggregation.Utils;
import de.fraunhofer.iosb.ilt.stp.utils.SensorThingsUtils;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author scf
 */
public class AggregationData {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AggregationData.class);

    private final SensorThingsService service;
    private ObservableList<AggregationBase> aggregationBases = FXCollections.observableArrayList();
    private Map<String, AggregationBase> aggregationBasesByName = new HashMap<>();
    private Map<String, List<AggregateCombo>> combosBySource;
    private ZoneId zoneId;
    private final boolean fixReferences;
    private final boolean addEmptyBases;
    private final boolean sourceEqualsTarget = true;

    public AggregationData(SensorThingsService service, boolean fixReferences, boolean addEmptyBases) {
        this.service = service;
        this.fixReferences = fixReferences;
        this.addEmptyBases = addEmptyBases;
    }

    public AggregationData(SensorThingsService service, boolean fixReferences) {
        this(service, fixReferences, false);
    }

    private AggregationBase getAggregationBase(String baseName) {
        AggregationBase aggBase = aggregationBasesByName.get(baseName);
        if (aggBase == null) {
            aggBase = new AggregationBase(baseName);
            aggregationBases.add(aggBase);
            aggregationBasesByName.put(baseName, aggBase);
        }
        return aggBase;
    }

    private void findAllBases() {
        try {
            Iterator<Datastream> datastreams = service.datastreams()
                    .query()
                    .select("id", "name", "description", "properties", "unitOfMeasurement")
                    .list()
                    .fullIterator();
            while (datastreams.hasNext()) {
                Datastream datastream = datastreams.next();
                String name = datastream.getName();
                String base = baseNameFromName(name);
                AggregationBase aggregationBase = getAggregationBase(base);
                aggregationBase.setBaseDatastream(datastream);
            }
        } catch (ServiceFailureException exc) {
            LOGGER.error("Service error loading Datastreams: ", exc);
        }
    }

    private void findTargetMultiDatastreams() {
        try {
            Iterator<Thing> things = service.things().query().list().fullIterator();
            while (things.hasNext()) {
                Thing thing = things.next();
                EntityList<MultiDatastream> dsList = thing.multiDatastreams().query().filter("endsWith(name, ']')").list();
                for (Iterator<MultiDatastream> it = dsList.fullIterator(); it.hasNext();) {
                    MultiDatastream mds = it.next();
                    String name = mds.getName();
                    Matcher matcher = Utils.POSTFIX_PATTERN.matcher(name);
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
                    AggregationBase aggBase = getAggregationBase(combo.baseName);
                    aggBase.addCombo(combo);
                }
            }
        } catch (ServiceFailureException exc) {
            LOGGER.error("Service error: ", exc);
        }
    }

    private void findSourceDatastreams(AggregateCombo target) {
        try {

            String nameQuoted = "'" + target.baseName.replaceAll("'", "''") + "'";
            {
                List<Datastream> list = service.datastreams().query().filter("name eq " + nameQuoted).list().toList();
                if (list.size() > 1) {
                    LOGGER.warn("Multiple ({}) sources found for {}.", list.size(), target.baseName);
                }
                if (list.size() > 0) {
                    target.sourceDs = list.get(0);
                    target.sourceIsAggregate = false;
                    checkReference(target.sourceDs, target.target, target.level);
                    return;
                }
            }
            {
                List<MultiDatastream> list = service.multiDatastreams().query().filter("name eq " + nameQuoted).list().toList();
                if (list.size() > 1) {
                    LOGGER.warn("Multiple ({}) sources found for {}.", list.size(), target.baseName);
                }
                if (list.size() > 0) {
                    target.sourceMds = list.get(0);
                    target.sourceIsAggregate = false;
                    checkReference(target.sourceMds, target.target, target.level);
                    return;
                }
            }
            {
                List<Datastream> list = service.datastreams().query().filter("startswith(name," + nameQuoted + ")").list().toList();
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
                    checkReference(target.sourceDs, target.target, target.level);
                    return;
                }
            }
            LOGGER.warn("No source found for {}.", target.baseName);
        } catch (ServiceFailureException ex) {
            LOGGER.error("Failed to find source for {}." + target.baseName);
            LOGGER.debug("Exception:", ex);
        }
    }

    private void findSourceDatastreams(AggregationBase base) {
        Set<AggregateCombo> comboSet = base.getCombos();
        AggregateCombo[] targets = comboSet.toArray(new AggregateCombo[comboSet.size()]);
        int i = 0;
        for (AggregateCombo target : targets) {
            int idx = i;
            boolean found = false;
            while (!found && idx > 0) {
                // check the other combos
                idx--;
                AggregateCombo test = targets[idx];
                long smaller = test.level.duration.getSeconds();
                long larger = target.level.duration.getSeconds();
                if (larger % smaller == 0) {
                    LOGGER.debug("{}: {} ~ {} ({})", target.baseName, target.level, test.level, (larger / smaller));
                    target.sourceMds = test.target;
                    target.sourceIsAggregate = true;
                    found = true;
                    checkReference(target.sourceMds, target.target, target.level);
                }
            }
            if (!found) {
                // No other combo is valid.
                findSourceDatastreams(target);
                if (target.sourceDs != null) {
                    base.setBaseDatastream(target.sourceDs);
                    base.setBaseMultiDatastream(null);
                } else if (base.getBaseDatastream() == null) {
                    base.setBaseMultiDatastream(target.sourceMds);
                }
            }
            i++;
            LOGGER.debug("Found source for: {}.", target);
        }
    }

    private void findSourceDatastreams(List<AggregationBase> bases) {
        for (AggregationBase base : bases) {
            findSourceDatastreams(base);

        }
    }

    private String baseNameFromName(String name) {
        Matcher matcher = Utils.POSTFIX_PATTERN.matcher(name);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return name;
    }

    private void gatherData() {
        if (addEmptyBases) {
            findAllBases();
            LOGGER.info("Found {} base names", aggregationBases.size());
        }
        // Find target multidatastreams
        findTargetMultiDatastreams();
        LOGGER.info("Found {} comboSets", aggregationBasesByName.size());
        // Find source datastreams matching the targets
        findSourceDatastreams(aggregationBases);

        combosBySource = new HashMap<>();
        for (AggregationBase base : aggregationBasesByName.values()) {
            for (AggregateCombo combo : base.getCombos()) {
                String path = combo.getSourceObsMqttPath();
                if (path.isEmpty()) {
                    continue;
                }
                List<AggregateCombo> bySource = combosBySource.get(path);
                if (bySource == null) {
                    bySource = new ArrayList<>();
                    combosBySource.put(path, bySource);
                }
                bySource.add(combo);
            }
        }
        LOGGER.info("Found {} unique source datastreams", combosBySource.size());
    }

    public ObservableList<AggregationBase> getAggregationBases() {
        if (aggregationBases.isEmpty()) {
            gatherData();
        }
        return aggregationBases;
    }

    public Map<String, AggregationBase> getCombosByBase() {
        if (aggregationBasesByName == null) {
            gatherData();
        }
        return aggregationBasesByName;
    }

    public Map<String, List<AggregateCombo>> getComboBySource() {
        return getComboBySource(false);
    }

    public Map<String, List<AggregateCombo>> getComboBySource(boolean recalculate) {
        if (combosBySource == null || recalculate) {
            gatherData();
        }
        return combosBySource;
    }

    private void checkReference(Datastream source, MultiDatastream aggregate, AggregationLevel level) {
        String expectedAggFor;
        if (sourceEqualsTarget) {
            expectedAggFor = "/Datastreams(" + source.getId().getUrl() + ")";
        } else {
            expectedAggFor = source.getSelfLink().toString();
        }
        checkReference(aggregate, expectedAggFor, level);
    }

    private void checkReference(MultiDatastream source, MultiDatastream aggregate, AggregationLevel level) {
        String expectedAggFor;
        if (sourceEqualsTarget) {
            expectedAggFor = "/MultiDatastreams(" + source.getId().getUrl() + ")";
        } else {
            expectedAggFor = source.getSelfLink().toString();
        }
        checkReference(aggregate, expectedAggFor, level);
    }

    private boolean checkProperty(Map<String, Object> properties, String property, Object value) {
        Object oldValue = properties.get(property);
        if (!(value instanceof Number) && !(value instanceof Boolean) && !(value instanceof String)) {
            value = value.toString();
        }
        if (!value.equals(oldValue)) {
            LOGGER.info("Fixing property {} not correct. Is {}, should be {}.", property, oldValue, value);
            properties.put(property, value);
            return true;
        }
        return false;
    }

    private void checkReference(MultiDatastream aggregate, String expectedAggFor, AggregationLevel level) {
        Map<String, Object> properties = aggregate.getProperties();
        if (properties == null) {
            properties = new HashMap<>();
            aggregate.setProperties(properties);
        }
        boolean changed = false;
        changed = changed || checkProperty(properties, SensorThingsUtils.KEY_AGGREGATE_AMOUNT, level.amount);
        changed = changed || checkProperty(properties, SensorThingsUtils.KEY_AGGREGATE_UNIT, level.unit);

        String aggFor = Objects.toString(properties.get(Utils.KEY_AGGREGATE_FOR));
        if (!expectedAggFor.equals(aggFor)) {
            if (fixReferences) {
                LOGGER.info("Setting source reference for {} to {}.", aggregate.getName(), expectedAggFor);
                properties.put(Utils.KEY_AGGREGATE_FOR, expectedAggFor);
                changed = true;
            } else {
                LOGGER.info("Source reference for {} not correct. Should be {}.", aggregate.getName(), expectedAggFor);
            }
        }
        if (changed && fixReferences) {
            try {
                service.update(aggregate);
            } catch (ServiceFailureException ex) {
                LOGGER.error("Failed to update reference.", ex);
            }
        }
    }

}