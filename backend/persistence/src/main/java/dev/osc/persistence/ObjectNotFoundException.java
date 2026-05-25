package dev.osc.persistence;

public class ObjectNotFoundException extends RuntimeException {

    public ObjectNotFoundException(String apiName) {
        super("Object definition not found: " + apiName);
    }
}
