package dev.osc.automation.dsl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recursive-descent parser for formula fields.
 * Supports math operators (+, -, *, /), parentheses, comparison, and logical operators.
 */
@Component
public class FormulaParser {

    private static final Pattern SECURITY_PATTERN =
            Pattern.compile("[\\[\\];{}]|\\b(new|import|class|interface|enum|extends|implements|return|throw|try|catch|finally|static|synchronized|volatile|transient|native|strictfp)\\b");

    private static final Pattern CROSS_OBJECT_DOT_PATTERN =
            Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_]");

    public FormulaNode parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }
        if (SECURITY_PATTERN.matcher(expression).find()) {
            throw new DslSecurityException(
                    "Expression contains disallowed constructs: " + expression);
        }
        // Explicitly reject cross-object references via dot notation
        if (CROSS_OBJECT_DOT_PATTERN.matcher(expression).find()) {
            throw new DslSecurityException(
                    "Cross-object references are not allowed: " + expression);
        }

        // Additional check: any dot must be part of a decimal number (i.e. surrounded by digits)
        int dotIndex = expression.indexOf('.');
        while (dotIndex != -1) {
            if (dotIndex == 0 || dotIndex == expression.length() - 1 ||
                !Character.isDigit(expression.charAt(dotIndex - 1)) ||
                !Character.isDigit(expression.charAt(dotIndex + 1))) {
                throw new DslSecurityException(
                        "Disallowed use of '.' in expression: " + expression);
            }
            dotIndex = expression.indexOf('.', dotIndex + 1);
        }

        List<String> tokens = tokenize(expression);
        Parser p = new Parser(tokens);
        FormulaNode node = p.parseExpr();
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
                if (j >= expression.length()) {
                    throw new IllegalArgumentException("Unclosed string literal");
                }
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
            if (c == '>' || c == '<' || c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')') {
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
            if (Character.isDigit(c)) {
                int j = i;
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

        FormulaNode parseExpr() {
            return parseOr();
        }

        FormulaNode parseOr() {
            FormulaNode left = parseAnd();
            while ("OR".equals(peek())) {
                consume();
                left = new FormulaNode.Or(left, parseAnd());
            }
            return left;
        }

        FormulaNode parseAnd() {
            FormulaNode left = parseNot();
            while ("AND".equals(peek())) {
                consume();
                left = new FormulaNode.And(left, parseNot());
            }
            return left;
        }

        FormulaNode parseNot() {
            if ("NOT".equals(peek())) {
                consume();
                return new FormulaNode.Not(parseNot());
            }
            return parseComp();
        }

        FormulaNode parseComp() {
            FormulaNode left = parseAdd();
            String peeked = peek();
            if ("==".equals(peeked) || "!=".equals(peeked) || ">".equals(peeked) ||
                ">=".equals(peeked) || "<".equals(peeked) || "<=".equals(peeked)) {
                String op = consume();
                left = new FormulaNode.Comparison(left, ComparisonOp.fromSymbol(op), parseAdd());
            }
            return left;
        }

        FormulaNode parseAdd() {
            FormulaNode left = parseMul();
            while ("+".equals(peek()) || "-".equals(peek())) {
                String op = consume();
                if ("+".equals(op)) {
                    left = new FormulaNode.Add(left, parseMul());
                } else {
                    left = new FormulaNode.Subtract(left, parseMul());
                }
            }
            return left;
        }

        FormulaNode parseMul() {
            FormulaNode left = parsePrimary();
            while ("*".equals(peek()) || "/".equals(peek())) {
                String op = consume();
                if ("*".equals(op)) {
                    left = new FormulaNode.Multiply(left, parsePrimary());
                } else {
                    left = new FormulaNode.Divide(left, parsePrimary());
                }
            }
            return left;
        }

        FormulaNode parsePrimary() {
            String token = peek();
            if (token == null) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }
            if ("-".equals(token)) {
                consume();
                return new FormulaNode.Subtract(new FormulaNode.Literal(0.0), parsePrimary());
            }
            if ("(".equals(token)) {
                consume(); // (
                FormulaNode inner = parseExpr();
                String closing = consume(); // )
                if (!")".equals(closing)) {
                    throw new IllegalArgumentException("Expected ')' but got: " + closing);
                }
                return inner;
            }
            consume();
            if (token.startsWith("\"") && token.endsWith("\"")) {
                return new FormulaNode.Literal(token.substring(1, token.length() - 1));
            }
            if ("true".equalsIgnoreCase(token)) return new FormulaNode.Literal(Boolean.TRUE);
            if ("false".equalsIgnoreCase(token)) return new FormulaNode.Literal(Boolean.FALSE);
            if ("null".equalsIgnoreCase(token)) return new FormulaNode.Literal(null);

            // Check if numeric
            if (Character.isDigit(token.charAt(0))) {
                try {
                    if (token.contains(".")) {
                        return new FormulaNode.Literal(Double.parseDouble(token));
                    } else {
                        return new FormulaNode.Literal(Long.parseLong(token));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid numeric literal: " + token, e);
                }
            }

            // Otherwise, it must be an identifier (field reference)
            return new FormulaNode.Identifier(token);
        }
    }
}
