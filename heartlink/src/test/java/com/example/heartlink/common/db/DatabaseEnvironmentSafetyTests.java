package com.example.heartlink.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseEnvironmentSafetyTests {

    @Test
    void releaseProfileAllowsValidateWithDemoSeedDisabled() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("release");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        env.setProperty("app.seed.demo-enabled", "false");
        env.setProperty("spring.sql.init.mode", "never");

        assertThatCode(() -> DatabaseMigrationAndSeedConfig.validateEnvironmentSafety(env))
                .doesNotThrowAnyException();
    }

    @Test
    void releaseProfileRejectsDemoSeed() {
        for (String profile : new String[]{"release", "prod", "production", "staging"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            env.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
            env.setProperty("app.seed.demo-enabled", "true");

            assertThatThrownBy(() -> DatabaseMigrationAndSeedConfig.validateEnvironmentSafety(env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[SEED-GUARD]");
        }
    }

    @Test
    void releaseProfileRejectsSqlInitAndNonValidateDdlAuto() {
        MockEnvironment sqlInitEnv = new MockEnvironment();
        sqlInitEnv.setActiveProfiles("release");
        sqlInitEnv.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        sqlInitEnv.setProperty("spring.sql.init.mode", "always");

        assertThatThrownBy(() -> DatabaseMigrationAndSeedConfig.validateEnvironmentSafety(sqlInitEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[SEED-GUARD]");

        MockEnvironment updateDdlEnv = new MockEnvironment();
        updateDdlEnv.setActiveProfiles("release");
        updateDdlEnv.setProperty("spring.jpa.hibernate.ddl-auto", "update");

        assertThatThrownBy(() -> DatabaseMigrationAndSeedConfig.validateEnvironmentSafety(updateDdlEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[DB-UPGRADE-GUARD]");
    }

    @Test
    void demoSeedRequiresExplicitDevOrLocalProfile() {
        MockEnvironment defaultEnv = new MockEnvironment();
        defaultEnv.setProperty("app.seed.demo-enabled", "true");

        assertThatThrownBy(() -> DatabaseMigrationAndSeedConfig.validateEnvironmentSafety(defaultEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires explicit 'dev' or 'local' profile");

        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");
        devEnv.setProperty("app.seed.demo-enabled", "true");

        assertThatCode(() -> DatabaseMigrationAndSeedConfig.validateEnvironmentSafety(devEnv))
                .doesNotThrowAnyException();
    }
}

