package dev.osc.query;

/** Thrown by QueryParser when the input does not conform to the DSL grammar. */
public class ParseException extends RuntimeException {

    private final int position;

    public ParseException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    public int getPosition() { return position; }
}
