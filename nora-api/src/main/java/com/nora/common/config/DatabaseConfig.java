package com.nora.common.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
public class DatabaseConfig {
    private static final String[] DOMAINS = {"agent", "file", "rag", "datasource", "env", "automation", "notification"};

    @Bean
    public DataSource dataSource(DataSourceProperties properties) {
        // Versions overlap between domains; each original schema keeps its own Flyway history.
        for (String domain : DOMAINS) {
            Flyway.configure().dataSource(properties.getUrl(), properties.getUsername(), properties.getPassword())
                    .schemas("schema_" + domain).defaultSchema("schema_" + domain)
                    .locations("classpath:db/migration/" + domain).placeholderReplacement(false)
                    .load().migrate();
        }
        HikariDataSource source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        source.setMaximumPoolSize(20);
        source.setConnectionInitSql("SET search_path TO schema_agent,schema_file,schema_rag,schema_datasource,schema_env,schema_automation,schema_notification,public");
        return source;
    }
}
