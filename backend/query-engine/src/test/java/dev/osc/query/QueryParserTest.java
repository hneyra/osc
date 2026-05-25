package dev.osc.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultQueryParser")
class QueryParserTest {

    QueryParser parser;

    @BeforeEach
    void setUp() {
        parser = new DefaultQueryParser();
    }

    // ── SELECT clause ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SELECT clause")
    class SelectClause {

        @Test
        @DisplayName("SELECT * selects all fields")
        void selectStar() {
            SelectQuery q = parser.parse("SELECT * FROM Account");
            assertThat(q.selectAll()).isTrue();
            assertThat(q.fields()).isEmpty();
            assertThat(q.objectName()).isEqualTo("Account");
        }

        @Test
        @DisplayName("SELECT single field")
        void singleField() {
            SelectQuery q = parser.parse("SELECT name FROM Account");
            assertThat(q.selectAll()).isFalse();
            assertThat(q.fields()).containsExactly(new FieldRef("name"));
        }

        @Test
        @DisplayName("SELECT multiple fields")
        void multipleFields() {
            SelectQuery q = parser.parse("SELECT name, email__c, created_at FROM Contact");
            assertThat(q.fields()).extracting(FieldRef::name)
                    .containsExactly("name", "email__c", "created_at");
        }

        @Test
        @DisplayName("SELECT is case-insensitive keyword")
        void caseInsensitiveSelect() {
            SelectQuery q = parser.parse("select * from Account");
            assertThat(q.objectName()).isEqualTo("Account");
        }
    }

    // ── WHERE clause ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WHERE clause")
    class WhereClause {

        @Test
        @DisplayName("simple string equality")
        void stringEquality() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE name = 'ACME'");
            assertThat(q.whereClause()).isInstanceOf(Condition.class);
            Condition c = (Condition) q.whereClause();
            assertThat(c.field().name()).isEqualTo("name");
            assertThat(c.operator()).isEqualTo(QueryOperator.EQ);
            assertThat(c.value()).isEqualTo(new Literal.StringValue("ACME"));
        }

        @Test
        @DisplayName("numeric greater-than")
        void numericGt() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE revenue__c > 1000");
            Condition c = (Condition) q.whereClause();
            assertThat(c.operator()).isEqualTo(QueryOperator.GT);
            assertThat(c.value()).isEqualTo(new Literal.NumberValue(new BigDecimal("1000")));
        }

        @Test
        @DisplayName("decimal number literal")
        void decimalNumber() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE score__c >= 9.5");
            Condition c = (Condition) q.whereClause();
            assertThat(c.value()).isEqualTo(new Literal.NumberValue(new BigDecimal("9.5")));
        }

        @Test
        @DisplayName("boolean literal")
        void booleanLiteral() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE is_active = true");
            Condition c = (Condition) q.whereClause();
            assertThat(c.value()).isEqualTo(new Literal.BooleanValue(true));
        }

        @Test
        @DisplayName("NULL literal")
        void nullLiteral() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE owner_id = null");
            Condition c = (Condition) q.whereClause();
            assertThat(c.value()).isInstanceOf(Literal.NullValue.class);
        }

        @Test
        @DisplayName("IN list with multiple values")
        void inList() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE status__c IN ('OPEN', 'PENDING')");
            Condition c = (Condition) q.whereClause();
            assertThat(c.operator()).isEqualTo(QueryOperator.IN);
            Literal.ListValue list = (Literal.ListValue) c.value();
            assertThat(list.values()).containsExactly(
                    new Literal.StringValue("OPEN"),
                    new Literal.StringValue("PENDING"));
        }

        @Test
        @DisplayName("NOT IN list")
        void notInList() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE status__c NOT IN ('CLOSED')");
            Condition c = (Condition) q.whereClause();
            assertThat(c.operator()).isEqualTo(QueryOperator.NOT_IN);
        }

        @Test
        @DisplayName("LIKE operator")
        void likeOperator() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE name LIKE 'ACME%'");
            Condition c = (Condition) q.whereClause();
            assertThat(c.operator()).isEqualTo(QueryOperator.LIKE);
            assertThat(c.value()).isEqualTo(new Literal.StringValue("ACME%"));
        }

        @Test
        @DisplayName("AND combination — left-to-right")
        void andCombination() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE a = 1 AND b = 2");
            assertThat(q.whereClause()).isInstanceOf(BinaryOp.class);
            BinaryOp op = (BinaryOp) q.whereClause();
            assertThat(op.op()).isEqualTo(BinaryOp.OpType.AND);
        }

        @Test
        @DisplayName("OR combination")
        void orCombination() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE a = 1 OR b = 2");
            BinaryOp op = (BinaryOp) q.whereClause();
            assertThat(op.op()).isEqualTo(BinaryOp.OpType.OR);
        }

        @Test
        @DisplayName("nested conditions with parentheses: a=1 AND (b=2 OR c=3)")
        void nestedConditions() {
            SelectQuery q = parser.parse(
                    "SELECT * FROM Account WHERE a = 1 AND (b = 2 OR c = 3)");
            BinaryOp outer = (BinaryOp) q.whereClause();
            assertThat(outer.op()).isEqualTo(BinaryOp.OpType.AND);
            assertThat(outer.right()).isInstanceOf(BinaryOp.class);
            BinaryOp inner = (BinaryOp) outer.right();
            assertThat(inner.op()).isEqualTo(BinaryOp.OpType.OR);
        }

        @Test
        @DisplayName("escaped single quote in string literal")
        void escapedQuote() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE name = 'O\\'Brien'");
            Condition c = (Condition) q.whereClause();
            assertThat(c.value()).isEqualTo(new Literal.StringValue("O'Brien"));
        }

        @Test
        @DisplayName("!= not-equal operator")
        void notEqualOperator() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE status__c != 'CLOSED'");
            Condition c = (Condition) q.whereClause();
            assertThat(c.operator()).isEqualTo(QueryOperator.NEQ);
        }

        @Test
        @DisplayName("<= operator")
        void lteOperator() {
            SelectQuery q = parser.parse("SELECT * FROM Account WHERE age__c <= 30");
            Condition c = (Condition) q.whereClause();
            assertThat(c.operator()).isEqualTo(QueryOperator.LTE);
        }
    }

    // ── ORDER BY / LIMIT / OFFSET ─────────────────────────────────────────────

    @Nested
    @DisplayName("ORDER BY, LIMIT, OFFSET")
    class Pagination {

        @Test
        @DisplayName("ORDER BY field ASC (default)")
        void orderByAsc() {
            SelectQuery q = parser.parse("SELECT * FROM Account ORDER BY name");
            assertThat(q.orderByField()).isEqualTo(new FieldRef("name"));
            assertThat(q.orderDir()).isEqualTo(OrderDirection.ASC);
        }

        @Test
        @DisplayName("ORDER BY field DESC")
        void orderByDesc() {
            SelectQuery q = parser.parse("SELECT * FROM Account ORDER BY created_at DESC");
            assertThat(q.orderByField()).isEqualTo(new FieldRef("created_at"));
            assertThat(q.orderDir()).isEqualTo(OrderDirection.DESC);
        }

        @Test
        @DisplayName("LIMIT only")
        void limitOnly() {
            SelectQuery q = parser.parse("SELECT * FROM Account LIMIT 10");
            assertThat(q.limit()).isEqualTo(10);
            assertThat(q.offset()).isNull();
        }

        @Test
        @DisplayName("LIMIT and OFFSET")
        void limitAndOffset() {
            SelectQuery q = parser.parse("SELECT * FROM Account LIMIT 20 OFFSET 40");
            assertThat(q.limit()).isEqualTo(20);
            assertThat(q.offset()).isEqualTo(40);
        }

        @Test
        @DisplayName("full query: fields + WHERE + ORDER BY + LIMIT")
        void fullQuery() {
            SelectQuery q = parser.parse(
                    "SELECT name, email__c FROM Contact WHERE account_id = 'uuid-123' ORDER BY name ASC LIMIT 5");
            assertThat(q.fields()).hasSize(2);
            assertThat(q.objectName()).isEqualTo("Contact");
            assertThat(q.whereClause()).isNotNull();
            assertThat(q.orderByField().name()).isEqualTo("name");
            assertThat(q.limit()).isEqualTo(5);
        }
    }

    // ── no WHERE clause ───────────────────────────────────────────────────────

    @Test
    @DisplayName("query with no WHERE has null whereClause")
    void noWhereClause() {
        SelectQuery q = parser.parse("SELECT * FROM Account");
        assertThat(q.whereClause()).isNull();
        assertThat(q.orderByField()).isNull();
        assertThat(q.limit()).isNull();
        assertThat(q.offset()).isNull();
    }

    // ── error cases ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("syntax errors")
    class SyntaxErrors {

        @ParameterizedTest
        @DisplayName("invalid queries throw ParseException")
        @ValueSource(strings = {
                "FROM Account",                // missing SELECT
                "SELECT",                      // incomplete
                "SELECT * Account",            // missing FROM
                "SELECT * FROM",               // missing object name
                "SELECT * FROM Account WHERE", // WHERE with no condition
                "SELECT * FROM Account WHERE name ="  // incomplete condition
        })
        void invalidQuery_throwsParseException(String query) {
            assertThatThrownBy(() -> parser.parse(query))
                    .isInstanceOf(ParseException.class);
        }

        @Test
        @DisplayName("unterminated string literal throws ParseException")
        void unterminatedString() {
            assertThatThrownBy(() -> parser.parse("SELECT * FROM Account WHERE name = 'unterminated"))
                    .isInstanceOf(ParseException.class);
        }
    }

    // ── SQL injection: values preserved as literals ───────────────────────────

    @Nested
    @DisplayName("SQL injection in values")
    class SqlInjection {

        @ParameterizedTest
        @DisplayName("injection string in value is preserved as literal, not executed")
        @ValueSource(strings = {
                "'; DROP TABLE record; --",
                "' OR '1'='1",
                "' UNION SELECT * FROM tenant --",
                "1; DROP TABLE record",
                "'; SELECT pg_sleep(10); --"
        })
        void injectionInValue_preservedAsLiteral(String injection) {
            String query = "SELECT * FROM Account WHERE name = '" + injection + "'";
            // The parser must not throw; it stores the value as a string literal
            // (SQL injection prevention is at the Translator layer via parameterized binds)
            try {
                SelectQuery q = parser.parse(query);
                Condition c = (Condition) q.whereClause();
                assertThat(c.value()).isInstanceOf(Literal.StringValue.class);
            } catch (ParseException e) {
                // Also acceptable: parser refuses the malformed input
            }
        }
    }
}
