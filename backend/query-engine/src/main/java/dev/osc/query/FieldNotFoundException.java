package dev.osc.query;

public class FieldNotFoundException extends RuntimeException {

    public FieldNotFoundException(String objectName, String fieldApiName) {
        super("Field '" + fieldApiName + "' not found on object '" + objectName + "'");
    }
}
