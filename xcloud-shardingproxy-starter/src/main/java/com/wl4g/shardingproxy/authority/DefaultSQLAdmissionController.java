/*
 * Copyright 2017 ~ 2025 the original author or authors. <wanglsir@gmail.com, 983708408@qq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wl4g.shardingproxy.authority;

import static com.wl4g.component.common.collection.CollectionUtils2.safeList;
import static com.wl4g.component.common.collection.CollectionUtils2.safeMap;
import static com.wl4g.component.common.reflect.ReflectionUtils2.findFieldNullable;
import static com.wl4g.component.common.reflect.ReflectionUtils2.getField;
import static com.wl4g.component.common.serialize.JacksonUtils.parseJSON;
import static java.lang.String.format;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.apache.shardingsphere.authority.provider.schema.SchemaPrivilegesPermittedAuthorityProviderAlgorithm;
import org.apache.shardingsphere.authority.rule.AuthorityRule;
import org.apache.shardingsphere.authority.spi.AuthorityProvideAlgorithm;
import org.apache.shardingsphere.infra.executor.check.SQLCheckResult;
import org.apache.shardingsphere.infra.metadata.user.Grantee;
import org.apache.shardingsphere.infra.metadata.user.ShardingSphereUser;
import org.apache.shardingsphere.proxy.frontend.command.CommandExecutorTask;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.AlterDatabaseStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.AlterTableStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.CreateDatabaseStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.CreateFunctionStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.CreateTableStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.DropDatabaseStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.DropTableStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.TruncateStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.DeleteStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.InsertStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.SelectStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.UpdateStatement;

import com.wl4g.shardingproxy.authority.DefaultSQLAdmissionController.AdmissionStrategyConfiguration.StrategySpec;
import com.wl4g.shardingproxy.util.ConfigPropertySource;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link DefaultSQLAdmissionController}
 * 
 * @author Wangl.sir &lt;wanglsir@gmail.com, 983708408@qq.com&gt;
 * @version 2022-01-21 v1.0.0
 * @since v1.0.0
 */
@Slf4j
@Getter
public class DefaultSQLAdmissionController implements SQLAdmissionController {
    private static final String PROP_KEY = "user-admission-strategy";
    private static final Field PROVIDER_FIELD = findFieldNullable(AuthorityRule.class, "provider",
            AuthorityProvideAlgorithm.class);
    private static final Field USERS_FIELD = findFieldNullable(AuthorityRule.class, "users", Collection.class);
    private static final Field PROPS_FIELD = findFieldNullable(SchemaPrivilegesPermittedAuthorityProviderAlgorithm.class, "props",
            Properties.class);
    private static final SQLCheckResult PASSED = new SQLCheckResult(true, null);

    private final SQLStatement sqlStatement;
    private final Grantee grantee;
    private final AuthorityRule authorityRule;
    private final AuthorityProvideAlgorithm provider;
    private final Collection<ShardingSphereUser> users;
    private final ConfigPropertySource props;
    private final AdmissionStrategyConfiguration strategyConfig;

    public DefaultSQLAdmissionController(final SQLStatement sqlStatement, final Grantee grantee,
            final AuthorityRule authorityRule) {
        this.sqlStatement = sqlStatement;
        this.grantee = grantee;
        this.authorityRule = authorityRule;
        this.provider = getField(PROVIDER_FIELD, authorityRule, true);
        this.users = getField(USERS_FIELD, authorityRule, true);
        this.props = (provider instanceof SchemaPrivilegesPermittedAuthorityProviderAlgorithm)
                ? new ConfigPropertySource(getField(PROPS_FIELD, provider, true))
                : new ConfigPropertySource();
        this.strategyConfig = AdmissionStrategyConfiguration.build(props.getProperty(PROP_KEY));
    }

    @Override
    public SQLCheckResult execute() {
        SQLCheckResult result = doAdmission0(sqlStatement);
        if (!result.isPassed()) {
            result = new SQLCheckResult(false, format("Access SQL failed to execute. - %s", result.getErrorMessage()));
            if (log.isWarnEnabled()) {
                log.warn("{}, statement: {}", sqlStatement, result.getErrorMessage());
            }
        }
        return result;
    }

    public SQLCheckResult doAdmission0(SQLStatement sqlStatement) {
        SQLCheckResult result = PASSED;
        // DML
        if (sqlStatement instanceof SelectStatement) {
            result = doSelectAdmission((SelectStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof InsertStatement) {
            result = doInsertAdmission((InsertStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof UpdateStatement) {
            result = doUpdateAdmission((UpdateStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof DeleteStatement) {
            result = doDeleteAdmission((DeleteStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        // DDL
        if (sqlStatement instanceof AlterDatabaseStatement) {
            result = doAlertDatabaseAdmission((AlterDatabaseStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof AlterTableStatement) {
            result = doAlertTableAdmission((AlterTableStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof CreateDatabaseStatement) {
            result = doCreateDatabaseAdmission((CreateDatabaseStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof CreateTableStatement) {
            result = doCreateTableAdmission((CreateTableStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof CreateFunctionStatement) {
            result = doCreateFunctionAdmission((CreateFunctionStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof DropDatabaseStatement) {
            result = doDropDatabaseAdmission((DropDatabaseStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof DropTableStatement) {
            result = doDropTableAdmission((DropTableStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        if (sqlStatement instanceof TruncateStatement) {
            result = doTruncateAdmission((TruncateStatement) sqlStatement);
            if (!result.isPassed()) {
                return result;
            }
        }
        // Regular expression blacklist blocking
        return doBlacklistRegexAdmission(sqlStatement);
    }

    private SQLCheckResult doSelectAdmission(SelectStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doInsertAdmission(InsertStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doUpdateAdmission(UpdateStatement statement) {
        return executeWithStrategyValiate(ss -> {
            if (ss.getUpdate().isRequiredWhereCondidtion() && !statement.getWhere().isPresent()) {
                return new SQLCheckResult(false, "Execute delete table empty condition the DML statement permission deined");
            }
            return PASSED;
        });
    }

    private SQLCheckResult doDeleteAdmission(DeleteStatement statement) {
        return executeWithStrategyValiate(ss -> {
            if (ss.getDelete().isRequiredWhereCondidtion() && !statement.getWhere().isPresent()) {
                return new SQLCheckResult(false, "Execute delete table empty condition the DML statement permission deined.");
            }
            return PASSED;
        });
    }

    private SQLCheckResult doAlertDatabaseAdmission(AlterDatabaseStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doAlertTableAdmission(AlterTableStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doCreateDatabaseAdmission(CreateDatabaseStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doCreateTableAdmission(CreateTableStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doCreateFunctionAdmission(CreateFunctionStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doDropDatabaseAdmission(DropDatabaseStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doDropTableAdmission(DropTableStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doTruncateAdmission(TruncateStatement statement) {
        return PASSED;
    }

    private SQLCheckResult doBlacklistRegexAdmission(SQLStatement statement) {
        final String sql = CommandExecutorTask.sqlCaching.get();
        CommandExecutorTask.sqlCaching.remove();
        if (isBlank(sql)) {
            return PASSED;
        }
        return executeWithStrategyValiate(ss -> {
            for (String regex : ss.getAnyBlacklistSQLs()) {
                Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                if (p.matcher(sql).matches()) {
                    return new SQLCheckResult(false, "Execute SQL statement of blocklist permission deined.");
                }
            }
            return PASSED;
        });
    }

    private SQLCheckResult executeWithStrategyValiate(Function<StrategySpec, SQLCheckResult> func) {
        for (StrategySpec ss : safeList(strategyConfig.getMerged().get(grantee.getUsername()))) {
            SQLCheckResult result = func.apply(ss);
            if (!result.isPassed()) {
                return result;
            }
        }
        return PASSED;
    }

    @Getter
    @Setter
    public static class AdmissionStrategyConfiguration {

        private Map<String, List<String>> granted = new HashMap<>();
        private Map<String, List<StrategySpec>> strategy = new HashMap<>();

        // Merged granted collection.
        private Map<String, Set<StrategySpec>> merged = new HashMap<>();

        public static AdmissionStrategyConfiguration build(final String json) {
            if (isBlank(json)) {
                return new AdmissionStrategyConfiguration();
            }
            AdmissionStrategyConfiguration that = parseJSON(json, AdmissionStrategyConfiguration.class);
            // Merging granted strategy.
            safeMap(that.getGranted()).forEach((username, strategyNames) -> that.getMerged().put(username,
                    safeMap(that.getStrategy()).entrySet().stream().filter(s -> safeList(strategyNames).contains(s.getKey()))
                            .map(e -> e.getValue()).flatMap(s -> s.stream()).collect(toSet())));
            return that;
        }

        @Getter
        @Setter
        public static class StrategySpec {
            private SelectSpec select = SelectSpec.EMPTY;
            private InsertSpec insert = InsertSpec.EMPTY;
            private UpdateSpec update = UpdateSpec.EMPTY;
            private DeleteSpec delete = DeleteSpec.EMPTY;
            private AlertDatabaseSpec alertDatabase = AlertDatabaseSpec.EMPTY;
            private AlertTableSpec alertTable = AlertTableSpec.EMPTY;
            private CreateDatabaseSpec createDatabase = CreateDatabaseSpec.EMPTY;
            private CreateTableSpec createTable = CreateTableSpec.EMPTY;
            private CreateFunctionSpec createFunction = CreateFunctionSpec.EMPTY;
            private DropDatabaseSpec dropDatabase = DropDatabaseSpec.EMPTY;
            private DropTableSpec dropTable = DropTableSpec.EMPTY;
            private TruncateSpec truncate = TruncateSpec.EMPTY;
            private List<String> anyBlacklistSQLs = new ArrayList<>();
        }

        @Getter
        @Setter
        public static class SelectSpec {
            public static final SelectSpec EMPTY = new SelectSpec();
        }

        @Getter
        @Setter
        public static class InsertSpec {
            public static final InsertSpec EMPTY = new InsertSpec();
        }

        @Getter
        @Setter
        public static class UpdateSpec {
            public static final UpdateSpec EMPTY = new UpdateSpec();
            private boolean requiredWhereCondidtion = false;
        }

        @Getter
        @Setter
        public static class DeleteSpec {
            public static final DeleteSpec EMPTY = new DeleteSpec();
            private boolean requiredWhereCondidtion = false;
        }

        @Getter
        @Setter
        public static class AlertDatabaseSpec {
            public static final AlertDatabaseSpec EMPTY = new AlertDatabaseSpec();
        }

        @Getter
        @Setter
        public static class AlertTableSpec {
            public static final AlertTableSpec EMPTY = new AlertTableSpec();
        }

        @Getter
        @Setter
        public static class CreateDatabaseSpec {
            public static final CreateDatabaseSpec EMPTY = new CreateDatabaseSpec();
        }

        @Getter
        @Setter
        public static class CreateTableSpec {
            public static final CreateTableSpec EMPTY = new CreateTableSpec();
        }

        @Getter
        @Setter
        public static class CreateFunctionSpec {
            public static final CreateFunctionSpec EMPTY = new CreateFunctionSpec();
        }

        @Getter
        @Setter
        public static class DropDatabaseSpec {
            public static final DropDatabaseSpec EMPTY = new DropDatabaseSpec();
        }

        @Getter
        @Setter
        public static class DropTableSpec {
            public static final DropTableSpec EMPTY = new DropTableSpec();
        }

        @Getter
        @Setter
        public static class TruncateSpec {
            public static final TruncateSpec EMPTY = new TruncateSpec();
        }
    }

}
