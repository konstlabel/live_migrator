package migrator.plan;

import migrator.ClassMigrator;
import migrator.exceptions.MigrateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MigrationPlan")
class MigrationPlanTest {

    // Test interfaces and classes
    interface User {
        int getId();
    }

    interface Entity {
        int getId();
    }

    static class OldUser implements User {
        private final int id;
        OldUser(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    static class NewUser implements User {
        private final int id;
        NewUser(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    static class OldEntity implements Entity {
        private final int id;
        OldEntity(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    static class NewEntity implements Entity {
        private final int id;
        NewEntity(int id) { this.id = id; }
        @Override
        public int getId() { return id; }
    }

    // Migrators
    public static class UserMigrator implements ClassMigrator<OldUser, NewUser> {
        @Override
        public NewUser migrate(OldUser old) {
            return new NewUser(old.getId());
        }
    }

    public static class EntityMigrator implements ClassMigrator<OldEntity, NewEntity> {
        @Override
        public NewEntity migrate(OldEntity old) {
            return new NewEntity(old.getId());
        }
    }

    // Duplicate migrator for same source
    public static class AnotherUserMigrator implements ClassMigrator<OldUser, NewUser> {
        @Override
        public NewUser migrate(OldUser old) {
            return new NewUser(old.getId() * 2);
        }
    }

    @Nested
    @DisplayName("build")
    class Build {

        @Test
        @DisplayName("should create plan with single migrator")
        void shouldCreatePlanWithSingleMigrator() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            assertThat(plan.hasMigration(OldUser.class)).isTrue();
            assertThat(plan.migratorFor(OldUser.class)).isEqualTo(descriptor);
            assertThat(plan.targetOf(OldUser.class)).isEqualTo(NewUser.class);
            assertThat(plan.orderedMigrators()).hasSize(1);
        }

        @Test
        @DisplayName("should create plan with multiple migrators")
        void shouldCreatePlanWithMultipleMigrators() throws MigrateException {
            MigratorDescriptor userDesc = new MigratorDescriptor(UserMigrator.class);
            MigratorDescriptor entityDesc = new MigratorDescriptor(EntityMigrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(userDesc, entityDesc));

            assertThat(plan.hasMigration(OldUser.class)).isTrue();
            assertThat(plan.hasMigration(OldEntity.class)).isTrue();
            assertThat(plan.orderedMigrators()).hasSize(2);
        }

        @Test
        @DisplayName("should create empty plan")
        void shouldCreateEmptyPlan() throws MigrateException {
            MigrationPlan plan = MigrationPlan.build(List.of());

            assertThat(plan.orderedMigrators()).isEmpty();
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("should reject duplicate migrators for same source")
        void shouldRejectDuplicateMigratorsForSameSource() {
            MigratorDescriptor desc1 = new MigratorDescriptor(UserMigrator.class);
            MigratorDescriptor desc2 = new MigratorDescriptor(AnotherUserMigrator.class);

            assertThatThrownBy(() -> MigrationPlan.build(List.of(desc1, desc2)))
                    .isInstanceOf(MigrateException.class)
                    .hasMessageContaining("Duplicate migrator for source class");
        }

        @Test
        @DisplayName("should reject null descriptors list")
        void shouldRejectNullDescriptorsList() {
            assertThatThrownBy(() -> MigrationPlan.build(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("queries")
    class Queries {

        @Test
        @DisplayName("should return false for unknown class")
        void shouldReturnFalseForUnknownClass() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            assertThat(plan.hasMigration(String.class)).isFalse();
            assertThat(plan.migratorFor(String.class)).isNull();
            assertThat(plan.targetOf(String.class)).isNull();
        }
    }

    @Nested
    @DisplayName("cycle detection")
    class CycleDetection {

        // For cycle testing we need classes that form a chain
        interface ChainA { }
        interface ChainB { }
        interface ChainC { }

        static class A1 implements ChainA { }
        static class A2 implements ChainA { }
        static class B1 implements ChainB { }
        static class B2 implements ChainB { }

        // A1 -> A2 (simple, no cycle)
        public static class A1ToA2Migrator implements ClassMigrator<A1, A2> {
            @Override
            public A2 migrate(A1 old) { return new A2(); }
        }

        @Test
        @DisplayName("should accept non-cyclic migrations")
        void shouldAcceptNonCyclicMigrations() throws MigrateException {
            MigratorDescriptor desc = new MigratorDescriptor(A1ToA2Migrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(desc));

            assertThat(plan.hasMigration(A1.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("topological ordering")
    class TopologicalOrdering {

        @Test
        @DisplayName("should order independent migrators")
        void shouldOrderIndependentMigrators() throws MigrateException {
            MigratorDescriptor userDesc = new MigratorDescriptor(UserMigrator.class);
            MigratorDescriptor entityDesc = new MigratorDescriptor(EntityMigrator.class);

            MigrationPlan plan = MigrationPlan.build(List.of(userDesc, entityDesc));

            // Both should be present, order doesn't matter for independent ones
            assertThat(plan.orderedMigrators()).containsExactlyInAnyOrder(userDesc, entityDesc);
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("orderedMigrators should return immutable list")
        void orderedMigratorsShouldReturnImmutableList() throws MigrateException {
            MigratorDescriptor descriptor = new MigratorDescriptor(UserMigrator.class);
            MigrationPlan plan = MigrationPlan.build(List.of(descriptor));

            List<MigratorDescriptor> migrators = plan.orderedMigrators();

            assertThatThrownBy(() -> migrators.add(descriptor))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
