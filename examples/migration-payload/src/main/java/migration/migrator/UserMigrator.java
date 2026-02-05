package migration.migrator;

import migrator.ClassMigrator;
import migrator.annotations.Migrator;
import migration.model.NewUser;
import service.model.OldUser;

@Migrator
public class UserMigrator implements ClassMigrator<OldUser, NewUser> {

    @Override
    public NewUser migrate(OldUser old) {
        return new NewUser(old.id, old.name);
    }
}
