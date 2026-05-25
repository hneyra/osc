package dev.osc.persistence;

public class FieldValidationException extends RuntimeException {

    private final String fieldApiName;
    private final String validationError;

    public FieldValidationException(String fieldApiName, String validationError) {
        super("Validation failed for field '" + fieldApiName + "': " + validationError);
        this.fieldApiName = fieldApiName;
        this.validationError = validationError;
    }

    public String getFieldApiName() { return fieldApiName; }
    public String getValidationError() { return validationError; }
}
