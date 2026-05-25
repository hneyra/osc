package dev.osc.automation.dsl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recursive-descent parser for the validation rule DSL.
 *
 * Grammar:
 *   expr     := or_expr
 *   or_expr  := and_expr ( "OR"  and_expr )*
 *   and_expr := not_expr ( "AND" not_expr )*
 *   not_expr := "NOT" not_expr | primary
 *   primary  := FIELD OP LITERAL
 *
 * Whitelist enforced:
 *   - FIELD: only [a-zA-Z_][a-zA-Z0-9_]* (no dots, no method calls)
 *   - OP: ==, !=, >, >=, <, <=
 *   - LITERAL: string in quotes, number, true, false
 */
@Component
public class ExpressionParser {

    private static final Pattern SECURITY_PATTERN =
            Pattern.compile("[.\\[\\]();{}]|\\b(new|import|class|interface|enum|extends|implements|return|throw|try|catch|finally|static|synchronized|volatile|transient|native|strictfp)\\b");

    public ExpressionNode parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }
        if (SECURITY_PATTERN.matcher(expression).find()) {
            throw new DslSecurityException(
                    "Expression contains disallowed constructs: " + expression);
        }
        List<String> tokens = tokenize(expression);
        Parser p = new Parser(tokens);
        ExpressionNode node = p.parseOr();
        if (p.hasMore()) {
            throw new IllegalArgumentException("Unexpected token: " + p.peek());
        }
        return node;
    }

    private List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // String literal
            if (c == '"') {
                int j = i + 1;
                while (j < expression.length() && expression.charAt(j) != '"') j++;
                tokens.add(expression.substring(i, j + 1));
                i = j + 1;
                continue;
            }
            // Two-char operators
            if (i + 1 < expression.length()) {
                String two = expression.substring(i, i + 2);
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=")) {
                    tokens.add(two);
                    i += 2;
                    continue;
                }
            }
            // Single-char operators
            if (c == '>' || c == '<') {
                tokens.add(String.valueOf(c));
                i++;
                continue;
            }
            // Identifier or keyword
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < expression.length() && (Character.isLetterOrDigit(expression.charAt(j)) || expression.charAt(j) == '_')) j++;
                tokens.add(expression.substring(i, j));
                i = j;
                continue;
            }
            // Number
            if (Character.isDigit(c) || (c == '-' && i + 1 < expression.length() && Character.isDigit(expression.charAt(i + 1)))) {
                int j = i;
                if (c == '-') j++;
                while (j < expression.length() && (Character.isDigit(expression.charAt(j)) || expression.charAt(j) == '.')) j++;
                tokens.add(expression.substring(i, j));
                i = j;
                continue;
            }
            throw new DslSecurityException("Unexpected character '" + c + "' at position " + i);
        }
        return tokens;
    }

    private static class Parser {
        private final List<String> tokens;
        private int pos;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean hasMore() { return pos < tokens.size(); }
        String peek() { return hasMore() ? tokens.get(pos) : null; }
        String consume() { return tokens.get(pos++); }

        ExpressionNode parseOr() {
            ExpressionNode left = parseAnd();
            while ("OR".equals(peek())) {
                consume();
                left = new ExpressionNode.Or(left, parseAnd());
            }
            return left;
        }

        ExpressionNode parseAnd() {
            ExpressionNode left = parseNot();
            while ("AND".equals(peek())) {
                consume();
                left = new ExpressionNode.And(left, parseNot());
            }
            return left;
        }

        ExpressionNode parseNot() {
            if ("NOT".equals(peek())) {
                consume();
                return new ExpressionNode.Not(parseNot());
            }
            return parsePrimary();
        }

        ExpressionNode parsePrimary() {
            String field = consume();
            String op = consume();
            String literalToken = consume();
            Object literal = parseLiteral(literalToken);
            return new ExpressionNode.Comparison(field, ComparisonOp.fromSymbol(op), literal);
        }

        private Object parseLiteral(String token) {
            if (token.startsWith("\"") && token.endsWith("\"")) {
                return token.substring(1, token.length() - 1);
            }
            if ("true".equalsIgnoreCase(token)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(token)) return Boolean.FALSE;
            if ("null".equalsIgnoreCase(token)) return null;
            try { return Long.parseLong(token); } catch (NumberFormatException ignored) {}
            try { return Double.parseDouble(token); } catch (NumberFormatException ignored) {}
            throw new IllegalArgumentException("Cannot parse literal: " + token);
        }
    }
}
