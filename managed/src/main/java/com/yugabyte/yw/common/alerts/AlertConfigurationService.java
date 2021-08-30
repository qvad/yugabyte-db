/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.common.alerts;

import static com.yugabyte.yw.common.Util.doubleToString;
import static com.yugabyte.yw.models.AlertConfiguration.createQueryByFilter;
import static com.yugabyte.yw.models.helpers.CommonUtils.nowWithoutMillis;
import static com.yugabyte.yw.models.helpers.CommonUtils.performPagedQuery;
import static com.yugabyte.yw.models.helpers.EntityOperation.CREATE;
import static com.yugabyte.yw.models.helpers.EntityOperation.DELETE;
import static com.yugabyte.yw.models.helpers.EntityOperation.UPDATE;
import static play.mvc.Http.Status.BAD_REQUEST;

import com.yugabyte.yw.common.AlertTemplate;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.alerts.impl.AlertConfigurationTemplate;
import com.yugabyte.yw.common.concurrent.MultiKeyLock;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.metrics.MetricLabelsBuilder;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.AlertConfiguration.SortBy;
import com.yugabyte.yw.models.AlertConfigurationTarget;
import com.yugabyte.yw.models.AlertConfigurationThreshold;
import com.yugabyte.yw.models.AlertDefinition;
import com.yugabyte.yw.models.AlertDestination;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.filters.AlertConfigurationFilter;
import com.yugabyte.yw.models.filters.AlertDefinitionFilter;
import com.yugabyte.yw.models.helpers.EntityOperation;
import com.yugabyte.yw.models.paging.AlertConfigurationPagedQuery;
import com.yugabyte.yw.models.paging.AlertConfigurationPagedResponse;
import com.yugabyte.yw.models.paging.PagedQuery.SortDirection;
import io.ebean.Query;
import io.ebean.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

@Singleton
@Slf4j
public class AlertConfigurationService {

  private static final int MAX_NAME_LENGTH = 1000;

  private final AlertDefinitionService alertDefinitionService;
  private final RuntimeConfigFactory runtimeConfigFactory;
  private final MultiKeyLock<AlertConfiguration> configUuidLock =
      new MultiKeyLock<>(Comparator.comparing(AlertConfiguration::getUuid));

  @Inject
  public AlertConfigurationService(
      AlertDefinitionService alertDefinitionService, RuntimeConfigFactory runtimeConfigFactory) {
    this.alertDefinitionService = alertDefinitionService;
    this.runtimeConfigFactory = runtimeConfigFactory;
  }

  @Transactional
  public List<AlertConfiguration> save(List<AlertConfiguration> configurations) {
    if (CollectionUtils.isEmpty(configurations)) {
      return configurations;
    }

    List<AlertConfiguration> beforeConfigurations = Collections.emptyList();
    Set<UUID> configurationUuids =
        configurations
            .stream()
            .filter(configuration -> !configuration.isNew())
            .map(AlertConfiguration::getUuid)
            .collect(Collectors.toSet());
    if (!configurationUuids.isEmpty()) {
      AlertConfigurationFilter filter =
          AlertConfigurationFilter.builder().uuids(configurationUuids).build();
      beforeConfigurations = list(filter);
    }
    Map<UUID, AlertConfiguration> beforeConfigMap =
        beforeConfigurations
            .stream()
            .collect(Collectors.toMap(AlertConfiguration::getUuid, Function.identity()));

    Map<EntityOperation, List<AlertConfiguration>> toCreateAndUpdate =
        configurations
            .stream()
            .peek(
                configuration ->
                    prepareForSave(configuration, beforeConfigMap.get(configuration.getUuid())))
            .peek(
                configuration ->
                    validate(configuration, beforeConfigMap.get(configuration.getUuid())))
            .collect(
                Collectors.groupingBy(configuration -> configuration.isNew() ? CREATE : UPDATE));

    List<AlertConfiguration> toCreate =
        toCreateAndUpdate.getOrDefault(CREATE, Collections.emptyList());
    toCreate.forEach(configuration -> configuration.setCreateTime(nowWithoutMillis()));
    toCreate.forEach(AlertConfiguration::generateUUID);

    List<AlertConfiguration> toUpdate =
        toCreateAndUpdate.getOrDefault(UPDATE, Collections.emptyList());

    try {
      configUuidLock.acquireLocks(configurations);
      if (!CollectionUtils.isEmpty(toCreate)) {
        AlertConfiguration.db().saveAll(toCreate);
      }

      if (!CollectionUtils.isEmpty(toUpdate)) {
        AlertConfiguration.db().updateAll(toUpdate);
      }

      manageDefinitions(configurations, beforeConfigurations);

      log.debug("{} alert configurations saved", configurations.size());
      return configurations;
    } finally {
      configUuidLock.releaseLocks(configurations);
    }
  }

  @Transactional
  public AlertConfiguration save(AlertConfiguration definition) {
    return save(Collections.singletonList(definition)).get(0);
  }

  public AlertConfiguration get(UUID uuid) {
    if (uuid == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Can't get Alert Configuration by null uuid");
    }
    return list(AlertConfigurationFilter.builder().uuid(uuid).build())
        .stream()
        .findFirst()
        .orElse(null);
  }

  public AlertConfiguration getOrBadRequest(UUID uuid) {
    if (uuid == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Invalid Alert Configuration UUID: " + uuid);
    }
    AlertConfiguration configuration = get(uuid);
    if (configuration == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Invalid Alert Configuration UUID: " + uuid);
    }
    return configuration;
  }

  public List<AlertConfiguration> list(AlertConfigurationFilter filter) {
    return createQueryByFilter(filter).findList();
  }

  public AlertConfigurationPagedResponse pagedList(AlertConfigurationPagedQuery pagedQuery) {
    if (pagedQuery.getSortBy() == null) {
      pagedQuery.setSortBy(SortBy.createTime);
      pagedQuery.setDirection(SortDirection.DESC);
    }
    Query<AlertConfiguration> query = createQueryByFilter(pagedQuery.getFilter()).query();
    return performPagedQuery(query, pagedQuery, AlertConfigurationPagedResponse.class);
  }

  public List<UUID> listIds(AlertConfigurationFilter filter) {
    return createQueryByFilter(filter).findIds();
  }

  public void process(AlertConfigurationFilter filter, Consumer<AlertConfiguration> consumer) {
    createQueryByFilter(filter).findEach(consumer);
  }

  @Transactional
  public void delete(UUID uuid) {
    AlertConfigurationFilter filter = AlertConfigurationFilter.builder().uuid(uuid).build();
    delete(filter);
  }

  @Transactional
  public void delete(Collection<AlertConfiguration> configurations) {
    if (CollectionUtils.isEmpty(configurations)) {
      return;
    }
    AlertConfigurationFilter filter =
        AlertConfigurationFilter.builder()
            .uuids(
                configurations
                    .stream()
                    .map(AlertConfiguration::getUuid)
                    .collect(Collectors.toSet()))
            .build();
    delete(filter);
  }

  public void delete(AlertConfigurationFilter filter) {
    List<AlertConfiguration> toDelete = list(filter);

    try {
      configUuidLock.acquireLocks(toDelete);
      manageDefinitions(Collections.emptyList(), toDelete);

      int deleted = createQueryByFilter(filter).delete();
      log.debug("{} alert definition configurations deleted", deleted);
    } finally {
      configUuidLock.releaseLocks(toDelete);
    }
  }

  private void prepareForSave(AlertConfiguration configuration, AlertConfiguration before) {
    if (before != null) {
      configuration.setCreateTime(before.getCreateTime());
    }
  }

  private void validate(AlertConfiguration configuration, AlertConfiguration before) {
    if (configuration.getCustomerUUID() == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Customer UUID field is mandatory");
    }
    if (StringUtils.isEmpty(configuration.getName())) {
      throw new PlatformServiceException(BAD_REQUEST, "Name field is mandatory");
    }
    if (configuration.getName().length() > MAX_NAME_LENGTH) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Name field can't be longer than " + MAX_NAME_LENGTH + " characters");
    }
    if (configuration.getTargetType() == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Target type field is mandatory");
    }
    if (configuration.getTarget() == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Target field is mandatory");
    }
    AlertConfigurationTarget target = configuration.getTarget();
    if (target.isAll() != CollectionUtils.isEmpty(target.getUuids())) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Should select either all entries or particular UUIDs as target");
    }
    if (!CollectionUtils.isEmpty(target.getUuids())) {
      boolean hasNulls = target.getUuids().stream().anyMatch(Objects::isNull);
      if (hasNulls) {
        throw new PlatformServiceException(BAD_REQUEST, "Target UUIDs can't have null values");
      }
      switch (configuration.getTargetType()) {
        case UNIVERSE:
          Set<UUID> existingUuids =
              Universe.getAllWithoutResources(configuration.getTarget().getUuids())
                  .stream()
                  .map(Universe::getUniverseUUID)
                  .collect(Collectors.toSet());
          Set<UUID> missingUuids = new HashSet<>(configuration.getTarget().getUuids());
          missingUuids.removeAll(existingUuids);
          if (!missingUuids.isEmpty()) {
            throw new PlatformServiceException(
                BAD_REQUEST,
                "Universe(s) missing for uuid(s) "
                    + missingUuids.stream().map(UUID::toString).collect(Collectors.joining(", ")));
          }
          break;
        default:
          throw new PlatformServiceException(
              BAD_REQUEST,
              configuration.getTargetType().name() + " configuration can't have target uuids");
      }
    }
    if (configuration.getTemplate() == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Template field is mandatory");
    }
    if (configuration.getTemplate().getTargetType() != configuration.getTargetType()) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Target type should be consistent with template");
    }
    if (MapUtils.isEmpty(configuration.getThresholds())) {
      throw new PlatformServiceException(BAD_REQUEST, "Query thresholds are mandatory");
    }
    if (configuration.getDestinationUUID() != null
        && AlertDestination.get(configuration.getCustomerUUID(), configuration.getDestinationUUID())
            == null) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Alert destination " + configuration.getDestinationUUID() + " is missing");
    }
    if (configuration.getThresholdUnit() == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Threshold unit is mandatory");
    }
    if (configuration.getThresholdUnit() != configuration.getTemplate().getDefaultThresholdUnit()) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Can't set threshold unit incompatible with alert definition template");
    }
    configuration
        .getThresholds()
        .values()
        .forEach(
            threshold -> {
              if (threshold.getCondition() == null) {
                throw new PlatformServiceException(BAD_REQUEST, "Threshold condition is mandatory");
              }
              if (threshold.getThreshold() == null) {
                throw new PlatformServiceException(BAD_REQUEST, "Threshold value is mandatory");
              }
              if (threshold.getThreshold() < configuration.getTemplate().getThresholdMinValue()) {
                throw new PlatformServiceException(
                    BAD_REQUEST,
                    "Threshold value can't be less than "
                        + doubleToString(configuration.getTemplate().getThresholdMinValue()));
              }
              if (threshold.getThreshold() > configuration.getTemplate().getThresholdMaxValue()) {
                throw new PlatformServiceException(
                    BAD_REQUEST,
                    "Threshold value can't be greater than "
                        + doubleToString(configuration.getTemplate().getThresholdMaxValue()));
              }
            });
    if (configuration.getDurationSec() == null || configuration.getDurationSec() < 0) {
      throw new PlatformServiceException(BAD_REQUEST, "Duration can't be less than 0");
    }
    if (before != null) {
      if (!configuration.getCustomerUUID().equals(before.getCustomerUUID())) {
        throw new PlatformServiceException(
            BAD_REQUEST, "Can't change customer UUID for configuration " + configuration.getUuid());
      }
      if (!configuration.getTargetType().equals(before.getTargetType())) {
        throw new PlatformServiceException(
            BAD_REQUEST, "Can't change target type for configuration " + configuration.getUuid());
      }
      if (!configuration.getCreateTime().equals(before.getCreateTime())) {
        throw new PlatformServiceException(
            BAD_REQUEST, "Can't change create time for configuration " + configuration.getUuid());
      }
    } else if (!configuration.isNew()) {
      throw new PlatformServiceException(
          BAD_REQUEST, "Can't update missing configuration " + configuration.getUuid());
    }
  }

  @Transactional
  public void handleSourceRemoval(
      UUID customerUuid, AlertConfiguration.TargetType configType, UUID targetUuid) {
    AlertConfigurationFilter filter =
        AlertConfigurationFilter.builder()
            .customerUuid(customerUuid)
            .targetType(configType)
            .build();

    List<AlertConfiguration> configurations =
        list(filter)
            .stream()
            .filter(
                configuration ->
                    configuration.getTarget().isAll()
                        || configuration.getTarget().getUuids().remove(targetUuid))
            .collect(Collectors.toList());

    Map<EntityOperation, List<AlertConfiguration>> toUpdateAndDelete =
        configurations
            .stream()
            .collect(
                Collectors.groupingBy(
                    configuration ->
                        configuration.getTarget().isAll()
                                || !configuration.getTarget().getUuids().isEmpty()
                            ? UPDATE
                            : DELETE));

    // Just need to save - service will delete definition itself.
    save(toUpdateAndDelete.get(UPDATE));
    delete(toUpdateAndDelete.get(DELETE));
  }

  /**
   * The purpose of this method is to manage alert definitions, related to modified definition alert
   * configurations. The main idea is to read existing definitions, remove the ones, which are not
   * needed anymore, and create new ones (universe is added or target list changed) and update
   * existing ones (in case group itself has changed).
   *
   * @param configurations List of new/updated configurations
   * @param beforeList List of configurations before update
   */
  private void manageDefinitions(
      List<AlertConfiguration> configurations, List<AlertConfiguration> beforeList) {
    // Make sure we process both new, updated and deleted configurations.
    Set<UUID> configurationUUIDs =
        Stream.concat(configurations.stream(), beforeList.stream())
            .map(AlertConfiguration::getUuid)
            .collect(Collectors.toSet());

    if (configurationUUIDs.isEmpty()) {
      return;
    }

    // First read existing alert definitions for all the affected configurations.
    AlertDefinitionFilter filter =
        AlertDefinitionFilter.builder().configurationUuids(configurationUUIDs).build();
    Map<UUID, List<AlertDefinition>> definitionsByConfiguration =
        alertDefinitionService
            .list(filter)
            .stream()
            .collect(
                Collectors.groupingBy(AlertDefinition::getConfigurationUUID, Collectors.toList()));

    List<AlertDefinition> toSave = new ArrayList<>();
    List<AlertDefinition> toRemove = new ArrayList<>();

    Map<UUID, AlertConfiguration> configurationsMap =
        configurations
            .stream()
            .collect(Collectors.toMap(AlertConfiguration::getUuid, Function.identity()));
    Map<UUID, AlertConfiguration> beforeMap =
        beforeList
            .stream()
            .collect(Collectors.toMap(AlertConfiguration::getUuid, Function.identity()));

    for (UUID uuid : configurationUUIDs) {
      AlertConfiguration configuration = configurationsMap.get(uuid);
      AlertConfiguration before = beforeMap.get(uuid);

      // List of existing definitions for particular configuration.
      List<AlertDefinition> currentDefinitions =
          definitionsByConfiguration.getOrDefault(uuid, Collections.emptyList());
      if (configuration == null) {
        // If configuration was deleted - remove all the associated definitions.
        toRemove.addAll(currentDefinitions);
      } else {
        boolean configurationChanged = before != null && !before.equals(configuration);
        Customer customer = Customer.getOrBadRequest(configuration.getCustomerUUID());
        AlertConfigurationTarget target = configuration.getTarget();
        switch (configuration.getTargetType()) {
          case PLATFORM:
            // For platform level configurations we always have only one definition
            // - linked to customer
            if (currentDefinitions.size() > 1) {
              throw new IllegalStateException(
                  "More than one definition for CUSTOMER alert definition configuration " + uuid);
            }
            AlertDefinition definition;
            if (currentDefinitions.isEmpty()) {
              // If it's missing - we need to create one. Probably config is just created.
              definition = createEmptyDefinition(configuration);
            } else {
              // If it exists - we need to update existing one just in case group is updated.
              definition = currentDefinitions.get(0);
            }
            definition.setQuery(configuration.getTemplate().buildTemplate(customer));
            if (!configuration.getTemplate().isSkipTargetLabels()) {
              definition.setLabels(
                  MetricLabelsBuilder.create().appendSource(customer).getDefinitionLabels());
            }
            toSave.add(definition);
            break;
          case UNIVERSE:
            // For universe level configurations we have a definition per universe.
            Set<UUID> universeUUIDs;
            Set<Universe> universes;

            if (target.isAll()) {
              // Get all universes + concat universe UUIDs from existing definitions for
              // the universes which were deleted.
              universes = Universe.getAllWithoutResources(customer);
              universeUUIDs =
                  Stream.concat(
                          currentDefinitions.stream().map(AlertDefinition::getUniverseUUID),
                          universes.stream().map(Universe::getUniverseUUID))
                      .collect(Collectors.toSet());
            } else {
              // Get target universes + universes from existing definitions for
              // the cases, when universe is not a target for this configuration anymore.
              universeUUIDs =
                  Stream.concat(
                          currentDefinitions.stream().map(AlertDefinition::getUniverseUUID),
                          target.getUuids().stream())
                      .collect(Collectors.toSet());
              universes = Universe.getAllWithoutResources(universeUUIDs);
            }
            Map<UUID, Universe> universeMap =
                universes
                    .stream()
                    .collect(Collectors.toMap(Universe::getUniverseUUID, Function.identity()));
            Map<UUID, List<AlertDefinition>> definitionsByUniverseUuid =
                currentDefinitions
                    .stream()
                    .collect(Collectors.groupingBy(AlertDefinition::getUniverseUUID));
            for (UUID universeUuid : universeUUIDs) {
              Universe universe = universeMap.get(universeUuid);
              List<AlertDefinition> universeDefinitions =
                  definitionsByUniverseUuid.get(universeUuid);
              // In case universe still exists and is in our target - we need to have definition.
              boolean shouldHaveDefinition =
                  (target.isAll() || target.getUuids().contains(universeUuid)) && universe != null;
              AlertDefinition universeDefinition;
              if (shouldHaveDefinition) {
                if (CollectionUtils.isEmpty(universeDefinitions)) {
                  // Either new universe is created or it's just added to the configuration target.
                  universeDefinition = createEmptyDefinition(configuration);
                } else {
                  universeDefinition = universeDefinitions.get(0);
                  if (universeDefinitions.size() > 1) {
                    log.warn(
                        "Have more than one definition for configuration {} universe {}",
                        uuid,
                        universeUuid);
                    toRemove.addAll(universeDefinitions.subList(1, universeDefinitions.size()));
                  }
                  if (!configurationChanged) {
                    // Universe had definition before the update and group is not changed.
                    // We want to avoid updating definitions unnecessarily.
                    continue;
                  }
                }
                universeDefinition.setConfigWritten(false);
                universeDefinition.setQuery(
                    configuration.getTemplate().buildTemplate(customer, universe));
                if (!configuration.getTemplate().isSkipTargetLabels()) {
                  universeDefinition.setLabels(
                      MetricLabelsBuilder.create().appendSource(universe).getDefinitionLabels());
                }
                toSave.add(universeDefinition);
              } else if (!CollectionUtils.isEmpty(universeDefinitions)) {
                // Remove existing definition if it's not needed.
                toRemove.addAll(universeDefinitions);
              }
            }
            break;
          default:
            throw new IllegalStateException(
                "Unexpected target type " + configuration.getTargetType());
        }
      }
    }

    if (!toSave.isEmpty()) {
      alertDefinitionService.save(toSave);
    }
    if (!toRemove.isEmpty()) {
      Set<UUID> uuids = toRemove.stream().map(AlertDefinition::getUuid).collect(Collectors.toSet());
      alertDefinitionService.delete(AlertDefinitionFilter.builder().uuids(uuids).build());
    }
  }

  public AlertConfigurationTemplate createConfigurationTemplate(
      Customer customer, AlertTemplate template) {
    AlertConfiguration configuration =
        new AlertConfiguration()
            .setCustomerUUID(customer.getUuid())
            .setName(template.getName())
            .setDescription(template.getDescription())
            .setTargetType(template.getTargetType())
            .setTarget(new AlertConfigurationTarget().setAll(true))
            .setThresholds(
                template
                    .getDefaultThresholdMap()
                    .entrySet()
                    .stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e ->
                                new AlertConfigurationThreshold()
                                    .setCondition(template.getDefaultThresholdCondition())
                                    .setThreshold(
                                        e.getValue().isParamName()
                                            ? runtimeConfigFactory
                                                .globalRuntimeConf()
                                                .getDouble(e.getValue().getParamName())
                                            : e.getValue().getThreshold()))))
            .setThresholdUnit(template.getDefaultThresholdUnit())
            .setTemplate(template)
            .setDurationSec(template.getDefaultDurationSec());
    return new AlertConfigurationTemplate()
        .setDefaultConfiguration(configuration)
        .setThresholdMinValue(template.getThresholdMinValue())
        .setThresholdMaxValue(template.getThresholdMaxValue())
        .setThresholdInteger(template.getDefaultThresholdUnit().isInteger());
  }

  private AlertDefinition createEmptyDefinition(AlertConfiguration configuration) {
    return new AlertDefinition()
        .setCustomerUUID(configuration.getCustomerUUID())
        .setConfigurationUUID(configuration.getUuid());
  }
}
