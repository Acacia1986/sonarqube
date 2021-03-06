/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.db.organization;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.ibatis.exceptions.PersistenceException;
import org.assertj.core.util.Lists;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.KeyLongValue;
import org.sonar.db.Pagination;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.dialect.Dialect;
import org.sonar.db.dialect.Oracle;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.qualitygate.QGateWithOrgDto;
import org.sonar.db.user.GroupDto;
import org.sonar.db.user.GroupTesting;
import org.sonar.db.user.UserDto;

import static com.google.common.collect.ImmutableSet.of;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.db.Pagination.forPage;
import static org.sonar.db.organization.OrganizationQuery.Builder;
import static org.sonar.db.organization.OrganizationQuery.newOrganizationQueryBuilder;
import static org.sonar.db.organization.OrganizationQuery.returnAll;
import static org.sonar.db.organization.OrganizationTesting.newOrganizationDto;

public class OrganizationDaoTest {
  private static final long SOME_DATE = 1_200_999L;
  private static final long DATE_1 = 1_999_000L;
  private static final long DATE_2 = 8_999_999L;
  private static final long DATE_3 = 3_999_000L;
  private static final OrganizationDto ORGANIZATION_DTO_1 = new OrganizationDto()
    .setUuid("uuid 1")
    .setKey("the_key 1")
    .setName("the name 1")
    .setDescription("the description 1")
    .setUrl("the url 1")
    .setAvatarUrl("the avatar url 1")
    .setGuarded(false)
    .setDefaultQualityGateUuid("1");
  private static final OrganizationDto ORGANIZATION_DTO_2 = new OrganizationDto()
    .setUuid("uuid 2")
    .setKey("the_key 2")
    .setName("the name 2")
    .setDescription("the description 2")
    .setUrl("the url 2")
    .setAvatarUrl("the avatar url 2")
    .setGuarded(true)
    .setDefaultQualityGateUuid("1");
  private static final String PERMISSION_1 = "foo";
  private static final String PERMISSION_2 = "bar";

  private System2 system2 = mock(System2.class);

  @Rule
  public final DbTester db = DbTester.create(system2).setDisableDefaultOrganization(true);
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private DbClient dbClient = db.getDbClient();
  private DbSession dbSession = db.getSession();

  private OrganizationDao underTest = dbClient.organizationDao();

  @Test
  public void insert_fails_with_NPE_if_OrganizationDto_is_null() {
    expectDtoCanNotBeNull();

    underTest.insert(dbSession, null, false);
  }

  @Test
  public void insert_populates_createdAt_and_updateAt_with_same_date_from_System2() {
    when(system2.now()).thenReturn(DATE_1, DATE_1 + 1_000_000L);
    insertOrganization(copyOf(ORGANIZATION_DTO_1)
      .setCreatedAt(1_000L)
      .setUpdatedAt(6_000L));

    Map<String, Object> row = selectSingleRow();
    assertThat(row.get("createdAt")).isEqualTo(DATE_1);
    assertThat(row.get("updatedAt")).isEqualTo(DATE_1);
  }

  @Test
  public void insert_persists_properties_of_OrganizationDto() {
    insertOrganization(ORGANIZATION_DTO_1);

    Map<String, Object> row = selectSingleRow();
    assertThat(row.get("uuid")).isEqualTo(ORGANIZATION_DTO_1.getUuid());
    assertThat(row.get("key")).isEqualTo(ORGANIZATION_DTO_1.getKey());
    assertThat(row.get("name")).isEqualTo(ORGANIZATION_DTO_1.getName());
    assertThat(row.get("description")).isEqualTo(ORGANIZATION_DTO_1.getDescription());
    assertThat(row.get("url")).isEqualTo(ORGANIZATION_DTO_1.getUrl());
    assertThat(row.get("avatarUrl")).isEqualTo(ORGANIZATION_DTO_1.getAvatarUrl());
    assertThat(row.get("createdAt")).isEqualTo(ORGANIZATION_DTO_1.getCreatedAt());
    assertThat(row.get("updatedAt")).isEqualTo(ORGANIZATION_DTO_1.getUpdatedAt());
    assertThat(row.get("guarded")).isEqualTo(toBool(ORGANIZATION_DTO_1.isGuarded()));
    assertThat(row.get("defaultTemplate")).isNull();
    assertThat(row.get("projectDefaultTemplate")).isNull();
    assertThat(row.get("viewDefaultTemplate")).isNull();
  }

  @Test
  public void insert_persists_boolean_property_guarded_of_OrganizationDto() {
    insertOrganization(ORGANIZATION_DTO_2);

    Map<String, Object> row = selectSingleRow();
    assertThat(row.get("guarded")).isEqualTo(toBool(ORGANIZATION_DTO_2.isGuarded()));
  }

  @Test
  public void description_url_avatarUrl_and_userId_are_optional() {
    when(system2.now()).thenReturn(SOME_DATE);
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setDescription(null).setUrl(null).setAvatarUrl(null));

    Map<String, Object> row = selectSingleRow();
    assertThat(row.get("uuid")).isEqualTo(ORGANIZATION_DTO_1.getUuid());
    assertThat(row.get("key")).isEqualTo(ORGANIZATION_DTO_1.getKey());
    assertThat(row.get("name")).isEqualTo(ORGANIZATION_DTO_1.getName());
    assertThat(row.get("description")).isNull();
    assertThat(row.get("url")).isNull();
    assertThat(row.get("avatarUrl")).isNull();
    assertThat(row.get("guarded")).isEqualTo(toBool(ORGANIZATION_DTO_1.isGuarded()));
    assertThat(row.get("userId")).isNull();
    assertThat(row.get("createdAt")).isEqualTo(SOME_DATE);
    assertThat(row.get("updatedAt")).isEqualTo(SOME_DATE);
    assertThat(row.get("defaultTemplate")).isNull();
    assertThat(row.get("projectDefaultTemplate")).isNull();
    assertThat(row.get("viewDefaultTemplate")).isNull();
  }

  private Object toBool(boolean guarded) {
    Dialect dialect = db.database().getDialect();
    if (dialect.getId().equals(Oracle.ID)) {
      return guarded ? 1L : 0L;
    }
    return guarded;
  }

  @Test
  public void insert_fails_if_row_with_uuid_already_exists() {
    insertOrganization(ORGANIZATION_DTO_1);

    OrganizationDto dto = new OrganizationDto()
      .setUuid(ORGANIZATION_DTO_1.getUuid())
      .setKey("other key")
      .setName("other name")
      .setCreatedAt(2_999_000L)
      .setUpdatedAt(2_888_000L);

    expectedException.expect(PersistenceException.class);

    underTest.insert(dbSession, dto, false);
  }

  @Test
  public void selectByKey_returns_empty_when_table_is_empty() {
    assertThat(underTest.selectByKey(dbSession, ORGANIZATION_DTO_1.getKey())).isEmpty();
  }

  @Test
  public void selectByKey_returns_row_data_when_key_exists() {
    insertOrganization(ORGANIZATION_DTO_1);

    Optional<OrganizationDto> optional = underTest.selectByKey(dbSession, ORGANIZATION_DTO_1.getKey());
    verifyOrganization1(optional);
  }

  @Test
  public void selectByKey_returns_row_data_of_specified_key() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.selectByKey(dbSession, "foo key")).isEmpty();
  }

  @Test
  public void selectByKey_is_case_sensitive() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.selectByKey(dbSession, ORGANIZATION_DTO_1.getKey().toUpperCase(Locale.ENGLISH))).isEmpty();
  }

  @Test
  public void selectByUuid_returns_empty_when_table_is_empty() {
    assertThat(underTest.selectByUuid(dbSession, ORGANIZATION_DTO_1.getUuid())).isEmpty();
  }

  @Test
  public void selectByUuid_returns_row_data_when_uuid_exists() {
    insertOrganization(ORGANIZATION_DTO_1);

    Optional<OrganizationDto> optional = underTest.selectByUuid(dbSession, ORGANIZATION_DTO_1.getUuid());
    verifyOrganization1(optional);
  }

  @Test
  public void selectByUuid_returns_row_data_of_specified_uuid() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.selectByUuid(dbSession, "foo uuid")).isEmpty();
  }

  @Test
  public void selectByUuid_is_case_sensitive() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.selectByUuid(dbSession, ORGANIZATION_DTO_1.getUuid().toUpperCase(Locale.ENGLISH))).isEmpty();
  }

  @Test
  public void selectByUuids_returns_empty_when_table_is_empty() {
    assertThat(underTest.selectByUuids(dbSession, of(ORGANIZATION_DTO_1.getUuid(), ORGANIZATION_DTO_2.getUuid())))
      .isEmpty();
  }

  @Test
  public void selectByUuids_returns_empty_when_argument_is_empty() {
    assertThat(underTest.selectByUuids(dbSession, Collections.emptySet()))
      .isEmpty();
  }

  @Test
  public void selectByUuids_returns_row_data_of_single_uuid_when_uuid_exists() {
    insertOrganization(ORGANIZATION_DTO_1);

    List<OrganizationDto> dtos = underTest.selectByUuids(dbSession, singleton(ORGANIZATION_DTO_1.getUuid()));
    assertThat(dtos).hasSize(1);
    verifyOrganization1(dtos.iterator().next());
  }

  @Test
  public void selectByUuids_returns_row_data_of_multiple_uuid_when_uuid_exists() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    List<OrganizationDto> dtos = underTest.selectByUuids(dbSession, of(ORGANIZATION_DTO_1.getUuid(), ORGANIZATION_DTO_2.getUuid()));
    assertThat(dtos).hasSize(2);
    verifyOrganization1(dtos.stream().filter((t) -> t.getUuid().equals(ORGANIZATION_DTO_1.getUuid())).findFirst().get());
    verifyOrganization(dtos.stream().filter((t) -> t.getUuid().equals(ORGANIZATION_DTO_2.getUuid())).findFirst().get(), ORGANIZATION_DTO_2);
  }

  @Test
  public void selectByUuids_returns_empty_when_no_uuid_exist() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    assertThat(underTest.selectByUuids(dbSession, of("foo uuid", "bar uuid")))
      .isEmpty();
  }

  @Test
  public void selectByUuids_returns_empty_when_no_single_uuid_exist() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    assertThat(underTest.selectByUuids(dbSession, of("foo uuid")))
      .isEmpty();
  }

  @Test
  public void selectByUuids_ignores_non_existing_uuids() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    List<OrganizationDto> dtos = underTest.selectByUuids(dbSession, of(ORGANIZATION_DTO_1.getUuid(), "foo uuid", ORGANIZATION_DTO_2.getUuid(), "bar uuid"));
    assertThat(dtos).extracting(OrganizationDto::getUuid).containsOnly(ORGANIZATION_DTO_1.getUuid(), ORGANIZATION_DTO_2.getUuid());
  }

  @Test
  public void selectByUuids_is_case_sensitive() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    assertThat(underTest.selectByUuids(
      dbSession,
      of(ORGANIZATION_DTO_1.getUuid().toUpperCase(Locale.ENGLISH), ORGANIZATION_DTO_2.getUuid().toUpperCase(Locale.ENGLISH))))
        .isEmpty();
  }

  @Test
  public void countByQuery() {
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid3").setKey("key-3"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid1").setKey("key-1"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid2").setKey("key-2"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid5").setKey("key-5"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid4").setKey("key-4"));

    assertThat(underTest.countByQuery(dbSession, returnAll())).isEqualTo(5);
    assertThat(underTest.countByQuery(dbSession, newQueryWithKeys("key-1", "key-2"))).isEqualTo(2);
    assertThat(underTest.countByQuery(dbSession, newQueryWithKeys("unknown"))).isZero();
  }

  @Test
  public void selectByQuery_returns_empty_when_table_is_empty() {
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(2).andSize(1))).isEmpty();
  }

  @Test
  public void selectByQuery_returns_single_row_of_table_when_requesting_first_page_of_size_1_or_more() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(1).andSize(1)))
      .hasSize(1)
      .extracting("uuid")
      .containsOnly(ORGANIZATION_DTO_1.getUuid());

    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(1).andSize(10)))
      .hasSize(1)
      .extracting("uuid")
      .containsOnly(ORGANIZATION_DTO_1.getUuid());
  }

  @Test
  public void selectByQuery_returns_empty_on_table_with_single_row_when_not_requesting_the_first_page() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(2).andSize(1))).isEmpty();
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(Math.abs(new Random().nextInt(10)) + 2).andSize(1))).isEmpty();
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(2).andSize(10))).isEmpty();
  }

  @Test
  public void selectByQuery_returns_rows_ordered_by_createdAt_descending_applying_requested_paging() {
    long time = 1_999_999L;
    when(system2.now()).thenReturn(time, time + 1_000, time + 2_000, time + 3_000, time + 5_000);
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid3").setKey("key-3"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid1").setKey("key-1"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid2").setKey("key-2"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid5").setKey("key-5"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid4").setKey("key-4"));

    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(1).andSize(1)))
      .extracting("uuid", "key")
      .containsExactly(tuple("uuid4", "key-4"));
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(2).andSize(1)))
      .extracting("uuid", "key")
      .containsExactly(tuple("uuid5", "key-5"));
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(3).andSize(1)))
      .extracting("uuid", "key")
      .containsExactly(tuple("uuid2", "key-2"));
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(4).andSize(1)))
      .extracting("uuid", "key")
      .containsExactly(tuple("uuid1", "key-1"));
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(5).andSize(1)))
      .extracting("uuid", "key")
      .containsExactly(tuple("uuid3", "key-3"));
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(6).andSize(1)))
      .isEmpty();

    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(1).andSize(5)))
      .extracting("uuid")
      .containsExactly("uuid4", "uuid5", "uuid2", "uuid1", "uuid3");
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(6).andSize(5)))
      .isEmpty();
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(1).andSize(3)))
      .extracting("uuid")
      .containsExactly("uuid4", "uuid5", "uuid2");
    assertThat(underTest.selectByQuery(dbSession, returnAll(), forPage(2).andSize(3)))
      .extracting("uuid")
      .containsExactly("uuid1", "uuid3");
  }

  @Test
  public void selectByQuery_with_keys_returns_empty_when_table_is_empty() {
    assertThat(underTest.selectByQuery(dbSession, newQueryWithKeys("key1", "key2"), forPage(2).andSize(1)))
      .isEmpty();
  }

  @Test
  public void selectByQuery_with_keys_returns_single_row_of_table_when_requesting_first_page_of_size_1_or_more() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    OrganizationQuery organizationQuery = newQueryWithKeys(ORGANIZATION_DTO_1.getKey(), ORGANIZATION_DTO_2.getKey());
    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(1).andSize(1)))
      .hasSize(1);

    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(1).andSize(10)))
      .hasSize(2)
      .extracting(OrganizationDto::getUuid)
      .containsOnly(ORGANIZATION_DTO_1.getUuid(), ORGANIZATION_DTO_2.getUuid());
  }

  @Test
  public void selectByQuery_with_empty_list_of_keys_returns_all() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    OrganizationQuery organizationQuery = newOrganizationQueryBuilder().setKeys(Lists.emptyList()).build();
    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(1).andSize(10)))
      .extracting(OrganizationDto::getUuid)
      .containsOnly(ORGANIZATION_DTO_1.getUuid(), ORGANIZATION_DTO_2.getUuid());
  }

  @Test
  public void selectByQuery_with_only_non_existent_keys_returns_empty() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    OrganizationQuery organizationQuery = newQueryWithKeys(PERMISSION_1, PERMISSION_2, "dog");
    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(1).andSize(10)))
      .isEmpty();
  }

  @Test
  public void selectByQuery_with_ignores_non_existent_keys() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    OrganizationQuery organizationQuery = newQueryWithKeys(ORGANIZATION_DTO_1.getKey(), PERMISSION_1, ORGANIZATION_DTO_2.getKey(), PERMISSION_2, "dog");
    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(1).andSize(10)))
      .hasSize(2)
      .extracting(OrganizationDto::getUuid)
      .containsOnly(ORGANIZATION_DTO_1.getUuid(), ORGANIZATION_DTO_2.getUuid());
  }

  @Test
  public void selectByQuery_with_keys_returns_empty_on_table_with_single_row_when_not_requesting_the_first_page() {
    insertOrganization(ORGANIZATION_DTO_1);
    insertOrganization(ORGANIZATION_DTO_2);

    OrganizationQuery organizationQuery = newQueryWithKeys(ORGANIZATION_DTO_1.getKey(), ORGANIZATION_DTO_2.getKey());
    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(2).andSize(2))).isEmpty();
    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(Math.abs(new Random().nextInt(10)) + 3).andSize(1))).isEmpty();
    assertThat(underTest.selectByQuery(dbSession, organizationQuery, forPage(3).andSize(10))).isEmpty();
  }

  @Test
  public void selectByQuery_with_keys_returns_rows_ordered_by_createdAt_descending_applying_requested_paging() {
    long time = 1_999_999L;
    when(system2.now()).thenReturn(time, time + 1_000, time + 2_000, time + 3_000, time + 5_000);
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid3").setKey("key-3"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid1").setKey("key-1"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid2").setKey("key-2"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid5").setKey("key-5"));
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid("uuid4").setKey("key-4"));
    OrganizationQuery allExistingKeys = newQueryWithKeys("key-1", "key-2", "key-3", "key-4", "key-5");

    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(1).andSize(1)))
      .extracting(OrganizationDto::getUuid, OrganizationDto::getKey)
      .containsExactly(tuple("uuid4", "key-4"));
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(2).andSize(1)))
      .extracting(OrganizationDto::getUuid, OrganizationDto::getKey)
      .containsExactly(tuple("uuid5", "key-5"));
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(3).andSize(1)))
      .extracting(OrganizationDto::getUuid, OrganizationDto::getKey)
      .containsExactly(tuple("uuid2", "key-2"));
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(4).andSize(1)))
      .extracting(OrganizationDto::getUuid, OrganizationDto::getKey)
      .containsExactly(tuple("uuid1", "key-1"));
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(5).andSize(1)))
      .extracting(OrganizationDto::getUuid, OrganizationDto::getKey)
      .containsExactly(tuple("uuid3", "key-3"));
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(6).andSize(1)))
      .isEmpty();

    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(1).andSize(5)))
      .extracting(OrganizationDto::getUuid)
      .containsExactly("uuid4", "uuid5", "uuid2", "uuid1", "uuid3");
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(2).andSize(5)))
      .isEmpty();
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(1).andSize(3)))
      .extracting(OrganizationDto::getUuid)
      .containsExactly("uuid4", "uuid5", "uuid2");
    assertThat(underTest.selectByQuery(dbSession, allExistingKeys, forPage(2).andSize(3)))
      .extracting(OrganizationDto::getUuid)
      .containsExactly("uuid1", "uuid3");
  }

  @Test
  public void selectByQuery_filter_on_a_member() {
    OrganizationDto organization = db.organizations().insert();
    OrganizationDto anotherOrganization = db.organizations().insert();
    OrganizationDto organizationWithoutMember = db.organizations().insert();
    UserDto user = db.users().insertUser();
    db.organizations().addMember(organization, user);
    db.organizations().addMember(anotherOrganization, user);

    List<OrganizationDto> result = underTest.selectByQuery(dbSession, newOrganizationQueryBuilder().setMember(user.getId()).build(), forPage(1).andSize(100));

    assertThat(result).extracting(OrganizationDto::getUuid)
      .containsExactlyInAnyOrder(organization.getUuid(), anotherOrganization.getUuid())
      .doesNotContain(organizationWithoutMember.getUuid());
  }

  @Test
  public void selectByQuery_filter_on_a_member_and_keys() {
    OrganizationDto organization = db.organizations().insert();
    OrganizationDto anotherOrganization = db.organizations().insert();
    OrganizationDto organizationWithoutKeyProvided = db.organizations().insert();
    OrganizationDto organizationWithoutMember = db.organizations().insert();
    UserDto user = db.users().insertUser();
    db.organizations().addMember(organization, user);
    db.organizations().addMember(anotherOrganization, user);
    db.organizations().addMember(organizationWithoutKeyProvided, user);

    List<OrganizationDto> result = underTest.selectByQuery(dbSession, newOrganizationQueryBuilder()
      .setKeys(Arrays.asList(organization.getKey(), anotherOrganization.getKey(), organizationWithoutMember.getKey()))
      .setMember(user.getId()).build(), forPage(1).andSize(100));

    assertThat(result).extracting(OrganizationDto::getUuid)
      .containsExactlyInAnyOrder(organization.getUuid(), anotherOrganization.getUuid())
      .doesNotContain(organizationWithoutKeyProvided.getUuid(), organizationWithoutMember.getUuid());
  }

  @Test
  public void selectByQuery_filter_on_type() {
    OrganizationDto personalOrg1 = db.organizations().insert();
    db.users().insertUser(u -> u.setOrganizationUuid(personalOrg1.getUuid()));
    OrganizationDto personalOrg2 = db.organizations().insert();
    db.users().insertUser(u -> u.setOrganizationUuid(personalOrg2.getUuid()));
    OrganizationDto teamOrg1 = db.organizations().insert();

    assertThat(selectUuidsByQuery(q -> q.setOnlyPersonal(), forPage(1).andSize(100)))
      .containsExactlyInAnyOrder(personalOrg1.getUuid(), personalOrg2.getUuid());
    assertThat(selectUuidsByQuery(q -> q.setOnlyTeam(), forPage(1).andSize(100)))
      .containsExactlyInAnyOrder(teamOrg1.getUuid());
  }

  @Test
  public void selectByQuery_filter_on_withAnalyses() {
    assertThat(selectUuidsByQuery(q -> q.setWithAnalyses(), forPage(1).andSize(100)))
      .isEmpty();

    // has projects and analyses
    OrganizationDto orgWithAnalyses = db.organizations().insert();
    ComponentDto analyzedProject = db.components().insertPrivateProject(orgWithAnalyses);
    db.components().insertSnapshot(analyzedProject, s -> s.setLast(true));
    // has projects but no analyses
    OrganizationDto orgWithProjects = db.organizations().insert();
    db.components().insertPrivateProject(orgWithProjects);
    db.components().insertPrivateProject(orgWithProjects, p -> p.setEnabled(false));
    // has no projects
    db.organizations().insert();
    // has only disabled projects
    OrganizationDto orgWithOnlyDisabledProjects = db.organizations().insert();
    db.components().insertPrivateProject(orgWithOnlyDisabledProjects, p -> p.setEnabled(false));

    assertThat(selectUuidsByQuery(q -> q.setWithAnalyses(), forPage(1).andSize(100)))
      .containsExactlyInAnyOrder(orgWithAnalyses.getUuid());
  }

  private List<String> selectUuidsByQuery(Consumer<Builder> query, Pagination pagination) {
    Builder builder = newOrganizationQueryBuilder();
    query.accept(builder);
    return underTest.selectByQuery(dbSession, builder.build(), pagination).stream()
      .map(OrganizationDto::getUuid)
      .collect(Collectors.toList());
  }

  @Test
  public void getDefaultTemplates_returns_empty_when_table_is_empty() {
    assertThat(underTest.getDefaultTemplates(dbSession, ORGANIZATION_DTO_1.getUuid())).isEmpty();
  }

  @Test
  public void getDefaultTemplates_returns_empty_when_row_exists_but_all_default_templates_columns_are_null() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.getDefaultTemplates(dbSession, ORGANIZATION_DTO_1.getUuid())).isEmpty();
  }

  @Test
  public void getDefaultTemplates_returns_data_when_project_default_templates_column_is_not_null() {
    insertOrganization(ORGANIZATION_DTO_1);
    underTest.setDefaultTemplates(dbSession, ORGANIZATION_DTO_1.getUuid(), new DefaultTemplates().setProjectUuid(PERMISSION_1));

    verifyGetDefaultTemplates(ORGANIZATION_DTO_1, PERMISSION_1, null);
  }

  @Test
  public void getDefaultTemplates_returns_data_when_project_and_view_default_template_column_are_not_null() {
    insertOrganization(ORGANIZATION_DTO_1);
    setDefaultTemplate(ORGANIZATION_DTO_1, PERMISSION_1, PERMISSION_2);

    verifyGetDefaultTemplates(ORGANIZATION_DTO_1, PERMISSION_1, PERMISSION_2);
  }

  @Test
  public void getDefaultTemplates_returns_empty_when_only_view_default_template_column_is_not_null() {
    dirtyInsertWithDefaultTemplate("uuid1", null, PERMISSION_2);

    assertThat(underTest.getDefaultTemplates(dbSession, "uuid1"))
      .isEmpty();
  }

  @Test
  public void getDefaultTemplates_returns_empty_when_project_and_view_default_template_column_are_not_null() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.getDefaultTemplates(dbSession, ORGANIZATION_DTO_1.getUuid()))
      .isEmpty();
  }

  @Test
  public void getDefaultTemplates_is_case_sensitive() {
    insertOrganization(ORGANIZATION_DTO_1);
    underTest.setDefaultTemplates(dbSession, ORGANIZATION_DTO_1.getUuid(), new DefaultTemplates().setProjectUuid(PERMISSION_1).setViewUuid(PERMISSION_2));

    assertThat(underTest.getDefaultTemplates(dbSession, ORGANIZATION_DTO_1.getUuid().toUpperCase(Locale.ENGLISH)))
      .isEmpty();
  }

  @Test
  public void setDefaultTemplates_throws_NPE_when_uuid_is_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("uuid can't be null");

    underTest.setDefaultTemplates(dbSession, null, new DefaultTemplates().setProjectUuid("p"));
  }

  @Test
  public void setDefaultTemplates_throws_NPE_when_defaultTemplate_is_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("defaultTemplates can't be null");

    underTest.setDefaultTemplates(dbSession, "uuid", null);
  }

  @Test
  public void setDefaultTemplates_throws_NPE_when_defaultTemplate_project_is_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("defaultTemplates.project can't be null");

    underTest.setDefaultTemplates(dbSession, "uuid", new DefaultTemplates());
  }

  @Test
  public void setDefaultTemplates_throws_NPE_when_defaultTemplate_project_is_null_and_view_is_not() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("defaultTemplates.project can't be null");

    underTest.setDefaultTemplates(dbSession, "uuid", new DefaultTemplates().setViewUuid(PERMISSION_1));
  }

  @Test
  public void getDefaultGroupId_returns_empty_when_default_group_id_is_null() {
    insertOrganization(ORGANIZATION_DTO_1.setDefaultGroupId(null));

    assertThat(underTest.getDefaultGroupId(dbSession, ORGANIZATION_DTO_1.getUuid())).isEmpty();
  }

  @Test
  public void getDefaultGroupId_returns_data_when_default_group_id_is_not_null() {
    when(system2.now()).thenReturn(DATE_3);
    insertOrganization(ORGANIZATION_DTO_1);
    underTest.setDefaultGroupId(dbSession, ORGANIZATION_DTO_1.getUuid(), GroupTesting.newGroupDto().setId(10));

    Optional<Integer> optional = underTest.getDefaultGroupId(dbSession, ORGANIZATION_DTO_1.getUuid());
    assertThat(optional).isNotEmpty();
    assertThat(optional.get()).isEqualTo(10);
    verifyOrganizationUpdatedAt(ORGANIZATION_DTO_1.getUuid(), DATE_3);
  }

  @Test
  public void setDefaultGroupId_throws_NPE_when_uuid_is_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("uuid can't be null");

    underTest.setDefaultGroupId(dbSession, null, GroupTesting.newGroupDto().setId(10));
  }

  @Test
  public void setDefaultGroupId_throws_NPE_when_default_group_is_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("Default group cannot be null");

    underTest.setDefaultGroupId(dbSession, "uuid", null);
  }

  @Test
  public void setDefaultGroupId_throws_NPE_when_default_group_id_is_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("Default group id cannot be null");

    underTest.setDefaultGroupId(dbSession, "uuid", GroupTesting.newGroupDto().setId(null));
  }

  @Test
  public void setDefaultQualityGate() {
    when(system2.now()).thenReturn(DATE_3);
    OrganizationDto organization = db.organizations().insert();
    QGateWithOrgDto qualityGate = db.qualityGates().insertQualityGate(organization);

    underTest.setDefaultQualityGate(dbSession, organization, qualityGate);
    db.commit();

    assertThat(dbClient.qualityGateDao().selectDefault(dbSession, organization).getUuid()).isEqualTo(qualityGate.getUuid());
    verifyOrganizationUpdatedAt(organization.getUuid(), DATE_3);
  }

  @Test
  public void update_fails_with_NPE_if_OrganizationDto_is_null() {
    expectDtoCanNotBeNull();

    underTest.update(dbSession, null);
  }

  @Test
  public void update_does_not_fail_if_specified_row_does_not_exist() {
    underTest.update(dbSession, ORGANIZATION_DTO_1);
  }

  @Test
  public void update_with_same_information_succeeds_but_has_no_effect() {
    insertOrganization(ORGANIZATION_DTO_1);

    underTest.update(dbSession, ORGANIZATION_DTO_1);
    dbSession.commit();

    Map<String, Object> row = selectSingleRow();
    assertThat(row.get("uuid")).isEqualTo(ORGANIZATION_DTO_1.getUuid());
    assertThat(row.get("key")).isEqualTo(ORGANIZATION_DTO_1.getKey());
    assertThat(row.get("name")).isEqualTo(ORGANIZATION_DTO_1.getName());
    assertThat(row.get("description")).isEqualTo(ORGANIZATION_DTO_1.getDescription());
    assertThat(row.get("url")).isEqualTo(ORGANIZATION_DTO_1.getUrl());
    assertThat(row.get("avatarUrl")).isEqualTo(ORGANIZATION_DTO_1.getAvatarUrl());
    assertThat(row.get("createdAt")).isEqualTo(ORGANIZATION_DTO_1.getCreatedAt());
    assertThat(row.get("updatedAt")).isEqualTo(ORGANIZATION_DTO_1.getUpdatedAt());
  }

  @Test
  public void update_populates_updatedAt_with_date_from_System2() {
    when(system2.now()).thenReturn(DATE_1);
    insertOrganization(ORGANIZATION_DTO_1);

    when(system2.now()).thenReturn(DATE_2);
    underTest.update(dbSession, copyOf(ORGANIZATION_DTO_1)
      .setUpdatedAt(2_000L));
    dbSession.commit();

    Map<String, Object> row = selectSingleRow();
    assertThat(row.get("createdAt")).isEqualTo(DATE_1);
    assertThat(row.get("updatedAt")).isEqualTo(DATE_2);
  }

  @Test
  public void update_does_not_update_createdAt() {
    when(system2.now()).thenReturn(DATE_1);
    insertOrganization(ORGANIZATION_DTO_1);

    when(system2.now()).thenReturn(DATE_3);
    underTest.update(dbSession, newOrganizationDto()
      .setUuid(ORGANIZATION_DTO_1.getUuid())
      .setKey("new key")
      .setName("new name")
      .setDescription("new description")
      .setUrl("new url")
      .setAvatarUrl("new avatar url")
      .setCreatedAt(2_000L)
      .setUpdatedAt(3_000L));
    dbSession.commit();

    Map<String, Object> row = selectSingleRow();
    assertThat(row.get("uuid")).isEqualTo(ORGANIZATION_DTO_1.getUuid());
    assertThat(row.get("key")).isEqualTo("new key");
    assertThat(row.get("name")).isEqualTo("new name");
    assertThat(row.get("description")).isEqualTo("new description");
    assertThat(row.get("url")).isEqualTo("new url");
    assertThat(row.get("avatarUrl")).isEqualTo("new avatar url");
    assertThat(row.get("createdAt")).isEqualTo(DATE_1);
    assertThat(row.get("updatedAt")).isEqualTo(DATE_3);
  }

  @Test
  public void update_fails_if_name_is_null() {
    insertOrganization(ORGANIZATION_DTO_1);

    expectedException.expect(PersistenceException.class);

    underTest.update(dbSession, copyOf(ORGANIZATION_DTO_1).setName(null));
  }

  @Test
  public void deleteByUuid_does_not_fail_on_empty_table() {
    assertThat(underTest.deleteByUuid(dbSession, "uuid")).isEqualTo(0);
    dbSession.commit();
  }

  @Test
  public void deleteByUuid_does_not_fail_on_non_existing_row() {
    insertOrganization(ORGANIZATION_DTO_1);

    assertThat(underTest.deleteByUuid(dbSession, "uuid")).isEqualTo(0);
    dbSession.commit();
  }

  @Test
  public void deleteByUuid_deletes_row_with_specified_uuid() {
    insertOrganization(ORGANIZATION_DTO_1);
    String anotherUuid = "uuid";
    insertOrganization(copyOf(ORGANIZATION_DTO_1).setUuid(anotherUuid).setKey("key"));

    assertThat(db.countRowsOfTable("organizations")).isEqualTo(2);
    assertThat(underTest.deleteByUuid(dbSession, anotherUuid)).isEqualTo(1);
    dbSession.commit();

    assertThat(underTest.selectByUuid(dbSession, anotherUuid)).isEmpty();
    assertThat(underTest.selectByUuid(dbSession, ORGANIZATION_DTO_1.getUuid())).isNotEmpty();
    assertThat(db.countRowsOfTable("organizations")).isEqualTo(1);

    assertThat(underTest.deleteByUuid(dbSession, anotherUuid)).isEqualTo(0);
    assertThat(underTest.deleteByUuid(dbSession, ORGANIZATION_DTO_1.getUuid())).isEqualTo(1);
    dbSession.commit();

    assertThat(underTest.selectByUuid(dbSession, ORGANIZATION_DTO_1.getUuid())).isEmpty();
    assertThat(db.countRowsOfTable("organizations")).isEqualTo(0);
  }

  @Test
  public void selectByPermission_returns_organization_when_user_has_ADMIN_user_permission_on_some_organization() {
    UserDto user = db.users().insertUser();
    OrganizationDto organization1 = db.organizations().insert();
    db.users().insertPermissionOnUser(organization1, user, PERMISSION_2);
    OrganizationDto organization2 = db.organizations().insert();
    db.users().insertPermissionOnUser(organization2, user, PERMISSION_2);
    UserDto otherUser = db.users().insertUser();
    OrganizationDto organization3 = db.organizations().insert();
    db.users().insertPermissionOnUser(organization3, otherUser, PERMISSION_2);

    assertThat(underTest.selectByPermission(dbSession, user.getId(), PERMISSION_2))
      .extracting(OrganizationDto::getUuid)
      .containsOnly(organization1.getUuid(), organization2.getUuid());

    assertThat(underTest.selectByPermission(dbSession, otherUser.getId(), PERMISSION_2))
      .extracting(OrganizationDto::getUuid)
      .containsOnly(organization3.getUuid());

    assertThat(underTest.selectByPermission(dbSession, 1234, PERMISSION_2))
      .isEmpty();
  }

  @Test
  public void selectByPermission_returns_organization_when_user_has_ADMIN_group_permission_on_some_organization() {
    UserDto user = db.users().insertUser();
    OrganizationDto organization1 = db.organizations().insert();
    GroupDto defaultGroup = db.users().insertGroup(organization1);
    db.users().insertPermissionOnGroup(defaultGroup, PERMISSION_1);
    db.users().insertMember(defaultGroup, user);
    OrganizationDto organization2 = db.organizations().insert();
    GroupDto group1 = db.users().insertGroup(organization2);
    db.users().insertPermissionOnGroup(group1, PERMISSION_1);
    db.users().insertMember(group1, user);
    UserDto otherUser = db.users().insertUser();
    OrganizationDto organization3 = db.organizations().insert();
    GroupDto group2 = db.users().insertGroup(organization3);
    db.users().insertPermissionOnGroup(group2, PERMISSION_1);
    db.users().insertMember(group2, otherUser);

    assertThat(underTest.selectByPermission(dbSession, user.getId(), PERMISSION_1))
      .extracting(OrganizationDto::getUuid)
      .containsOnly(organization1.getUuid(), organization2.getUuid());

    assertThat(underTest.selectByPermission(dbSession, otherUser.getId(), PERMISSION_1))
      .extracting(OrganizationDto::getUuid)
      .containsOnly(organization3.getUuid());

    assertThat(underTest.selectByPermission(dbSession, 1234, PERMISSION_1))
      .isEmpty();
  }

  @Test
  public void selectByPermission_return_organization_only_once_even_if_user_has_ADMIN_permission_twice_or_more() {
    String permission = "destroy";
    UserDto user = db.users().insertUser();
    OrganizationDto organization = db.organizations().insert();
    GroupDto group1 = db.users().insertGroup(organization);
    db.users().insertPermissionOnGroup(group1, permission);
    db.users().insertMember(group1, user);
    GroupDto group2 = db.users().insertGroup(organization);
    db.users().insertPermissionOnGroup(group2, permission);
    db.users().insertMember(group2, user);
    db.users().insertPermissionOnUser(organization, user, permission);

    assertThat(underTest.selectByPermission(dbSession, user.getId(), permission))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(organization.getUuid());
  }

  @Test
  public void selectByPermission_returns_organization_only_if_user_has_specific_permission_by_user_permission() {
    OrganizationDto organization = db.organizations().insert();
    OrganizationDto otherOrganization = db.organizations().insert();
    UserDto user = db.users().insertUser();
    db.users().insertPermissionOnUser(organization, user, PERMISSION_1);
    db.users().insertPermissionOnUser(otherOrganization, user, PERMISSION_2);
    UserDto otherUser = db.users().insertUser();
    db.users().insertPermissionOnUser(organization, otherUser, PERMISSION_2);
    db.users().insertPermissionOnUser(otherOrganization, otherUser, PERMISSION_1);

    assertThat(underTest.selectByPermission(dbSession, user.getId(), PERMISSION_1))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(organization.getUuid());
    assertThat(underTest.selectByPermission(dbSession, user.getId(), PERMISSION_2))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(otherOrganization.getUuid());
    assertThat(underTest.selectByPermission(dbSession, otherUser.getId(), PERMISSION_1))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(otherOrganization.getUuid());
    assertThat(underTest.selectByPermission(dbSession, otherUser.getId(), PERMISSION_2))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(organization.getUuid());
  }

  @Test
  public void selectByPermission_returns_organization_only_if_user_has_specific_permission_by_group_permission() {
    OrganizationDto organization = db.organizations().insert();
    OrganizationDto otherOrganization = db.organizations().insert();
    GroupDto group1 = db.users().insertGroup(organization);
    GroupDto group2 = db.users().insertGroup(organization);
    GroupDto otherGroup1 = db.users().insertGroup(otherOrganization);
    GroupDto otherGroup2 = db.users().insertGroup(otherOrganization);
    db.users().insertPermissionOnGroup(group1, PERMISSION_1);
    db.users().insertPermissionOnGroup(otherGroup2, PERMISSION_2);
    db.users().insertPermissionOnGroup(group2, PERMISSION_2);
    db.users().insertPermissionOnGroup(otherGroup1, PERMISSION_1);
    UserDto user = db.users().insertUser();
    db.users().insertMember(group1, user);
    db.users().insertMember(otherGroup2, user);
    UserDto otherUser = db.users().insertUser();
    db.users().insertMember(group2, otherUser);
    db.users().insertMember(otherGroup1, otherUser);

    assertThat(underTest.selectByPermission(dbSession, user.getId(), PERMISSION_1))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(organization.getUuid());
    assertThat(underTest.selectByPermission(dbSession, user.getId(), PERMISSION_2))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(otherOrganization.getUuid());
    assertThat(underTest.selectByPermission(dbSession, otherUser.getId(), PERMISSION_1))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(otherOrganization.getUuid());
    assertThat(underTest.selectByPermission(dbSession, otherUser.getId(), PERMISSION_2))
      .extracting(OrganizationDto::getUuid)
      .containsOnlyOnce(organization.getUuid());
  }

  @Test
  public void countTeamsByMembers_on_zero_orgs() {
    assertThat(underTest.countTeamsByMembers(dbSession))
      .extracting(KeyLongValue::getKey, KeyLongValue::getValue)
      .containsExactlyInAnyOrder(
        tuple("0", 0L),
        tuple("1", 0L),
        tuple("2-4", 0L),
        tuple("5-9", 0L),
        tuple("10-24", 0L),
        tuple("+25", 0L));
  }

  @Test
  public void countTeamsByMembers() {
    UserDto user1 = db.users().insertUser();
    UserDto user2 = db.users().insertUser();
    UserDto user3 = db.users().insertUser();
    OrganizationDto org1 = db.organizations().insert();
    db.organizations().addMember(org1, user1, user2, user3);
    OrganizationDto org2 = db.organizations().insert();
    db.organizations().addMember(org2, user1);
    OrganizationDto org3 = db.organizations().insert();
    db.organizations().addMember(org3, user1, user2);

    assertThat(underTest.countTeamsByMembers(dbSession))
      .extracting(KeyLongValue::getKey, KeyLongValue::getValue)
      .containsExactlyInAnyOrder(
        tuple("0", 0L),
        tuple("1", 1L),
        tuple("2-4", 2L),
        tuple("5-9", 0L),
        tuple("10-24", 0L),
        tuple("+25", 0L));
  }

  @Test
  public void countTeamsByProjects_on_zero_projects() {
    assertThat(underTest.countTeamsByProjects(dbSession))
      .extracting(KeyLongValue::getKey, KeyLongValue::getValue)
      .containsExactlyInAnyOrder(
        tuple("0", 0L),
        tuple("1", 0L),
        tuple("2-4", 0L),
        tuple("5-9", 0L),
        tuple("10-24", 0L),
        tuple("+25", 0L));
  }

  @Test
  public void countTeamsByProjects() {
    OrganizationDto org1 = db.organizations().insert();
    db.components().insertPrivateProject(org1);
    OrganizationDto org2 = db.organizations().insert();
    db.components().insertPrivateProject(org2);
    db.components().insertPrivateProject(org2);
    OrganizationDto org3 = db.organizations().insert();
    db.components().insertPrivateProject(org3);
    db.components().insertPrivateProject(org3);
    db.components().insertPrivateProject(org3);

    assertThat(underTest.countTeamsByProjects(dbSession))
      .extracting(KeyLongValue::getKey, KeyLongValue::getValue)
      .containsExactlyInAnyOrder(
        tuple("0", 0L),
        tuple("1", 1L),
        tuple("2-4", 2L),
        tuple("5-9", 0L),
        tuple("10-24", 0L),
        tuple("+25", 0L));
  }

  @Test
  public void countTeamsByNclocRanges() {
    MetricDto ncloc = db.measures().insertMetric(m -> m.setKey(CoreMetrics.NCLOC_KEY));

    OrganizationDto org1 = db.organizations().insert();
    // project with highest ncloc in non-main branch
    ComponentDto project1 = db.components().insertMainBranch(org1);
    ComponentDto project1Branch = db.components().insertProjectBranch(project1);
    db.measures().insertLiveMeasure(project1, ncloc, m -> m.setValue(1_000.0));
    db.measures().insertLiveMeasure(project1Branch, ncloc, m -> m.setValue(110_000.0));
    // project with only main branch
    ComponentDto project2 = db.components().insertMainBranch(org1);
    db.measures().insertLiveMeasure(project2, ncloc, m -> m.setValue(400_000.0));

    OrganizationDto org2 = db.organizations().insert();
    // project with highest ncloc in main branch
    ComponentDto project3 = db.components().insertMainBranch(org2);
    ComponentDto project3Branch = db.components().insertProjectBranch(project3);
    db.measures().insertLiveMeasure(project3, ncloc, m -> m.setValue(5_800_000.0));
    db.measures().insertLiveMeasure(project3Branch, ncloc, m -> m.setValue(25_000.0));

    assertThat(underTest.countTeamsByNclocRanges(dbSession))
      .extracting(KeyLongValue::getKey, KeyLongValue::getValue)
      .containsExactlyInAnyOrder(
        tuple("100K", 0L),
        tuple("500K", 1L),
        tuple("1M", 0L),
        tuple("2M", 0L),
        tuple("5M", 0L),
        tuple("10M", 1L),
        tuple("20M", 0L),
        tuple("50M", 0L),
        tuple("+50M", 0L));
  }

  @Test
  public void countTeamsByNclocRanges_on_zero_orgs() {
    assertThat(underTest.countTeamsByNclocRanges(dbSession))
      .extracting(KeyLongValue::getKey, KeyLongValue::getValue)
      .containsExactlyInAnyOrder(
        tuple("100K", 0L),
        tuple("500K", 0L),
        tuple("1M", 0L),
        tuple("2M", 0L),
        tuple("5M", 0L),
        tuple("10M", 0L),
        tuple("20M", 0L),
        tuple("50M", 0L),
        tuple("+50M", 0L));
  }

  private void expectDtoCanNotBeNull() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("OrganizationDto can't be null");
  }

  private void insertOrganization(OrganizationDto dto) {
    underTest.insert(dbSession, dto, false);
    dbSession.commit();
  }

  private void dirtyInsertWithDefaultTemplate(String organizationUuid, @Nullable String project, @Nullable String view) {
    try (Connection connection = db.database().getDataSource().getConnection();
      PreparedStatement preparedStatement = connection.prepareStatement(
        "insert into organizations" +
          "    (" +
          "      uuid," +
          "      kee," +
          "      name," +
          "      default_perm_template_project," +
          "      default_perm_template_view," +
          "      new_project_private," +
          "      guarded," +
          "      default_quality_gate_uuid," +
          "      created_at," +
          "      updated_at" +
          "    )" +
          "    values" +
          "    (" +
          "      ?," +
          "      ?," +
          "      ?," +
          "      ?," +
          "      ?," +
          "      ?," +
          "      ?," +
          "      ?," +
          "      ?," +
          "      ?" +
          "    )")) {
      preparedStatement.setString(1, organizationUuid);
      preparedStatement.setString(2, organizationUuid);
      preparedStatement.setString(3, organizationUuid);
      preparedStatement.setString(4, project);
      preparedStatement.setString(5, view);
      preparedStatement.setBoolean(6, false);
      preparedStatement.setBoolean(7, false);
      preparedStatement.setString(8, "1"); // TODO check ok ?
      preparedStatement.setLong(9, 1000L);
      preparedStatement.setLong(10, 2000L);
      preparedStatement.execute();
    } catch (SQLException e) {
      throw new RuntimeException("dirty insert failed", e);
    }
  }

  private void setDefaultTemplate(OrganizationDto organizationDto1, @Nullable String project, @Nullable String view) {
    underTest.setDefaultTemplates(dbSession, organizationDto1.getUuid(), new DefaultTemplates().setProjectUuid(project).setViewUuid(view));
    dbSession.commit();
  }

  private void verifyOrganization1(Optional<OrganizationDto> optional) {
    assertThat(optional).isNotEmpty();
    verifyOrganization1(optional.get());
  }

  private void verifyOrganization1(OrganizationDto dto) {
    assertThat(dto.getUuid()).isEqualTo(ORGANIZATION_DTO_1.getUuid());
    assertThat(dto.getKey()).isEqualTo(ORGANIZATION_DTO_1.getKey());
    assertThat(dto.getName()).isEqualTo(ORGANIZATION_DTO_1.getName());
    assertThat(dto.getDescription()).isEqualTo(ORGANIZATION_DTO_1.getDescription());
    assertThat(dto.getUrl()).isEqualTo(ORGANIZATION_DTO_1.getUrl());
    assertThat(dto.isGuarded()).isEqualTo(ORGANIZATION_DTO_1.isGuarded());
    assertThat(dto.getAvatarUrl()).isEqualTo(ORGANIZATION_DTO_1.getAvatarUrl());
    assertThat(dto.getCreatedAt()).isEqualTo(ORGANIZATION_DTO_1.getCreatedAt());
    assertThat(dto.getUpdatedAt()).isEqualTo(ORGANIZATION_DTO_1.getUpdatedAt());
  }

  private void verifyOrganization(OrganizationDto dto, OrganizationDto expected) {
    assertThat(dto.getUuid()).isEqualTo(expected.getUuid());
    assertThat(dto.getKey()).isEqualTo(expected.getKey());
    assertThat(dto.getName()).isEqualTo(expected.getName());
    assertThat(dto.getDescription()).isEqualTo(expected.getDescription());
    assertThat(dto.getUrl()).isEqualTo(expected.getUrl());
    assertThat(dto.isGuarded()).isEqualTo(expected.isGuarded());
    assertThat(dto.getAvatarUrl()).isEqualTo(expected.getAvatarUrl());
    assertThat(dto.getCreatedAt()).isEqualTo(expected.getCreatedAt());
    assertThat(dto.getUpdatedAt()).isEqualTo(expected.getUpdatedAt());
  }

  private Map<String, Object> selectSingleRow() {
    List<Map<String, Object>> rows = db.select("select" +
      " uuid as \"uuid\", kee as \"key\", name as \"name\",  description as \"description\", url as \"url\", avatar_url as \"avatarUrl\"," +
      " guarded as \"guarded\"," +
      " created_at as \"createdAt\", updated_at as \"updatedAt\"," +
      " default_perm_template_project as \"projectDefaultPermTemplate\"," +
      " default_perm_template_view as \"viewDefaultPermTemplate\"," +
      " default_quality_gate_uuid as \"defaultQualityGateUuid\" " +
      " from organizations");
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  private OrganizationDto copyOf(OrganizationDto organizationDto) {
    return new OrganizationDto()
      .setUuid(organizationDto.getUuid())
      .setKey(organizationDto.getKey())
      .setName(organizationDto.getName())
      .setDescription(organizationDto.getDescription())
      .setUrl(organizationDto.getUrl())
      .setDefaultQualityGateUuid(organizationDto.getDefaultQualityGateUuid())
      .setAvatarUrl(organizationDto.getAvatarUrl());
  }

  private static OrganizationQuery newQueryWithKeys(String... keys) {
    return newOrganizationQueryBuilder().setKeys(Arrays.asList(keys)).build();
  }

  private void verifyGetDefaultTemplates(OrganizationDto organizationDto,
    @Nullable String expectedProject, @Nullable String expectedView) {
    Optional<DefaultTemplates> optional = underTest.getDefaultTemplates(dbSession, organizationDto.getUuid());
    assertThat(optional).isNotEmpty();
    DefaultTemplates defaultTemplates = optional.get();
    assertThat(defaultTemplates.getProjectUuid()).isEqualTo(expectedProject);
    assertThat(defaultTemplates.getViewUuid()).isEqualTo(expectedView);
  }

  private void verifyOrganizationUpdatedAt(String organization, Long updatedAt) {
    Map<String, Object> row = db.selectFirst(db.getSession(), String.format("select updated_at as \"updatedAt\" from organizations where uuid='%s'", organization));
    assertThat(row.get("updatedAt")).isEqualTo(updatedAt);
  }
}
