package migration.model;

import service.model.User;

public class NewUser implements User {

    public final int id;
    public final String name;

    public NewUser(int id, String name) {
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
        return "NewUser{id=" + id + ", name='" + name + "'}";
    }
}
