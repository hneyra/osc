package dev.osc.query;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written recursive-descent parser for the SOQL-like DSL.
 *
 * Grammar (implemented):
 *   query       := SELECT fields FROM objectName [WHERE conditions]
 *                  [ORDER BY field [ASC|DESC]] [LIMIT n] [OFFSET n]
 *   fields      := '*' | fieldName (',' fieldName)*
 *   conditions  := orExpr
 *   orExpr      := andExpr ('OR' andExpr)*
 *   andExpr     := primary ('AND' primary)*
 *   primary     := '(' conditions ')' | condition
 *   condition   := fieldName operator literal
 *               | fieldName 'NOT' 'IN' '(' literal (',' literal)* ')'
 *               | fieldName 'IN' '(' literal (',' literal)* ')'
 *
 * Design: pure parsing — no DB access, no metadata validation.
 * GoF pattern: Template Method in parsePrimary / parseOr / parseAnd chain.
 */
@Component
public class DefaultQueryParser implements QueryParser {

    @Override
    public SelectQuery parse(String query) {
        List<Token> tokens = new Lexer(query).tokenize();
        return new ParserState(tokens).parseSelect();
    }

    // ── Lexer ─────────────────────────────────────────────────────────────────

    private enum TokType {
        SELECT, FROM, WHERE, ORDER, BY, LIMIT, OFFSET,
        AND, OR, NOT, IN, LIKE, ASC, DESC,
        NULL, TRUE, FALSE,
        IDENTIFIER, STRING, INTEGER, DECIMAL,
        EQ, NEQ, LT, GT, LTE, GTE,
        COMMA, LPAREN, RPAREN, STAR, EOF
    }

    private record Token(TokType type, String value, int position) {}

    private static final class Lexer {
        private final String input;
        private int pos = 0;

        Lexer(String input) { this.input = input; }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (pos < input.length()) {
                skipWhitespace();
                if (pos >= input.length()) break;
                tokens.add(nextToken());
            }
            tokens.add(new Token(TokType.EOF, "", pos));
            return tokens;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        private Token nextToken() {
            int start = pos;
            char c = input.charAt(pos);

            if (Character.isLetter(c) || c == '_') return readWord(start);
            if (Character.isDigit(c))               return readNumber(start);
            if (c == '\'')                           return readString(start);

            pos++;
            return switch (c) {
                case '=' -> new Token(TokType.EQ,    "=",  start);
                case ',' -> new Token(TokType.COMMA, ",",  start);
                case '(' -> new Token(TokType.LPAREN,"(",  start);
                case ')' -> new Token(TokType.RPAREN,")",  start);
                case '*' -> new Token(TokType.STAR,  "*",  start);
                case '!' -> {
                    if (pos < input.length() && input.charAt(pos) == '=') { pos++; yield new Token(TokType.NEQ, "!=", start); }
                    throw new ParseException("Unexpected character '!'", start);
                }
                case '<' -> {
                    if (pos < input.length() && input.charAt(pos) == '=') { pos++; yield new Token(TokType.LTE, "<=", start); }
                    yield new Token(TokType.LT, "<", start);
                }
                case '>' -> {
                    if (pos < input.length() && input.charAt(pos) == '=') { pos++; yield new Token(TokType.GTE, ">=", start); }
                    yield new Token(TokType.GT, ">", start);
                }
                default -> throw new ParseException("Unexpected character '" + c + "'", start);
            };
        }

        private Token readWord(int start) {
            int begin = pos;
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
            String word = input.substring(begin, pos);
            TokType type = switch (word.toUpperCase()) {
                case "SELECT" -> TokType.SELECT;
                case "FROM"   -> TokType.FROM;
                case "WHERE"  -> TokType.WHERE;
                case "ORDER"  -> TokType.ORDER;
                case "BY"     -> TokType.BY;
                case "LIMIT"  -> TokType.LIMIT;
                case "OFFSET" -> TokType.OFFSET;
                case "AND"    -> TokType.AND;
                case "OR"     -> TokType.OR;
                case "NOT"    -> TokType.NOT;
                case "IN"     -> TokType.IN;
                case "LIKE"   -> TokType.LIKE;
                case "ASC"    -> TokType.ASC;
                case "DESC"   -> TokType.DESC;
                case "NULL"   -> TokType.NULL;
                case "TRUE"   -> TokType.TRUE;
                case "FALSE"  -> TokType.FALSE;
                default       -> TokType.IDENTIFIER;
            };
            return new Token(type, word, start);
        }

        private Token readNumber(int start) {
            int begin = pos;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
                return new Token(TokType.DECIMAL, input.substring(begin, pos), start);
            }
            return new Token(TokType.INTEGER, input.substring(begin, pos), start);
        }

        private Token readString(int start) {
            pos++; // skip opening '
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '\\' && pos + 1 < input.length() && input.charAt(pos + 1) == '\'') {
                    sb.append('\'');
                    pos += 2;
                } else if (c == '\'') {
                    pos++;
                    return new Token(TokType.STRING, sb.toString(), start);
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new ParseException("Unterminated string literal", start);
        }
    }

    // ── Recursive descent parser ──────────────────────────────────────────────

    private static final class ParserState {
        private final List<Token> tokens;
        private int cur = 0;

        ParserState(List<Token> tokens) { this.tokens = tokens; }

        private Token peek()            { return tokens.get(cur); }
        private Token advance()         { return tokens.get(cur++); }
        private boolean check(TokType t){ return peek().type() == t; }

        private Token expect(TokType t) {
            if (!check(t)) {
                Token at = peek();
                throw new ParseException("Expected " + t + " but found '" + at.value() + "'", at.position());
            }
            return advance();
        }

        SelectQuery parseSelect() {
            expect(TokType.SELECT);

            boolean selectAll = false;
            List<FieldRef> fields = new ArrayList<>();
            if (check(TokType.STAR)) {
                advance();
                selectAll = true;
            } else {
                fields.add(new FieldRef(expect(TokType.IDENTIFIER).value()));
                while (check(TokType.COMMA)) {
                    advance();
                    fields.add(new FieldRef(expect(TokType.IDENTIFIER).value()));
                }
            }

            expect(TokType.FROM);
            String objectName = expect(TokType.IDENTIFIER).value();

            QueryNode where = null;
            if (check(TokType.WHERE)) { advance(); where = parseOr(); }

            FieldRef orderBy = null;
            OrderDirection dir = OrderDirection.ASC;
            if (check(TokType.ORDER)) {
                advance();
                expect(TokType.BY);
                orderBy = new FieldRef(expect(TokType.IDENTIFIER).value());
                if (check(TokType.DESC)) { advance(); dir = OrderDirection.DESC; }
                else if (check(TokType.ASC)) { advance(); }
            }

            Integer limit = null;
            if (check(TokType.LIMIT)) { advance(); limit = Integer.parseInt(expect(TokType.INTEGER).value()); }

            Integer offset = null;
            if (check(TokType.OFFSET)) { advance(); offset = Integer.parseInt(expect(TokType.INTEGER).value()); }

            expect(TokType.EOF);
            return new SelectQuery(fields, selectAll, objectName, where, orderBy, dir, limit, offset);
        }

        private QueryNode parseOr() {
            QueryNode left = parseAnd();
            while (check(TokType.OR)) {
                advance();
                QueryNode right = parseAnd();
                left = new BinaryOp(left, BinaryOp.OpType.OR, right);
            }
            return left;
        }

        private QueryNode parseAnd() {
            QueryNode left = parsePrimary();
            while (check(TokType.AND)) {
                advance();
                QueryNode right = parsePrimary();
                left = new BinaryOp(left, BinaryOp.OpType.AND, right);
            }
            return left;
        }

        private QueryNode parsePrimary() {
            if (check(TokType.LPAREN)) {
                advance();
                QueryNode inner = parseOr();
                expect(TokType.RPAREN);
                return inner;
            }
            return parseCondition();
        }

        private Condition parseCondition() {
            FieldRef field = new FieldRef(expect(TokType.IDENTIFIER).value());

            QueryOperator op;
            if (check(TokType.NOT)) {
                advance();
                expect(TokType.IN);
                op = QueryOperator.NOT_IN;
            } else if (check(TokType.IN)) {
                advance();
                op = QueryOperator.IN;
            } else {
                op = parseOp();
            }

            Literal value;
            if (op == QueryOperator.IN || op == QueryOperator.NOT_IN) {
                expect(TokType.LPAREN);
                List<Literal> items = new ArrayList<>();
                items.add(parseLiteral());
                while (check(TokType.COMMA)) { advance(); items.add(parseLiteral()); }
                expect(TokType.RPAREN);
                value = new Literal.ListValue(items);
            } else {
                value = parseLiteral();
            }
            return new Condition(field, op, value);
        }

        private QueryOperator parseOp() {
            Token t = advance();
            return switch (t.type()) {
                case EQ   -> QueryOperator.EQ;
                case NEQ  -> QueryOperator.NEQ;
                case LT   -> QueryOperator.LT;
                case GT   -> QueryOperator.GT;
                case LTE  -> QueryOperator.LTE;
                case GTE  -> QueryOperator.GTE;
                case LIKE -> QueryOperator.LIKE;
                default   -> throw new ParseException("Expected operator but found '" + t.value() + "'", t.position());
            };
        }

        private Literal parseLiteral() {
            Token t = peek();
            return switch (t.type()) {
                case STRING  -> { advance(); yield new Literal.StringValue(t.value()); }
                case INTEGER -> { advance(); yield new Literal.NumberValue(new BigDecimal(t.value())); }
                case DECIMAL -> { advance(); yield new Literal.NumberValue(new BigDecimal(t.value())); }
                case TRUE    -> { advance(); yield new Literal.BooleanValue(true); }
                case FALSE   -> { advance(); yield new Literal.BooleanValue(false); }
                case NULL    -> { advance(); yield new Literal.NullValue(); }
                default -> throw new ParseException("Expected literal but found '" + t.value() + "'", t.position());
            };
        }
    }
}
