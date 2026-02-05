package service.model;

/**
 * Original User implementation used by the service before migration.
 *
 * <p>During migration, all OldUser instances in the heap are converted
 * to NewUser instances by the migration engine.
 *
 * @see User
 * @see migration.migrator.UserMigrator
 */
public class OldUser implements User {
    public final int id;
    public final String name;

    public OldUser(int id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "OldUser{id=" + id + ", name='" + name + "'}";
    }
}
