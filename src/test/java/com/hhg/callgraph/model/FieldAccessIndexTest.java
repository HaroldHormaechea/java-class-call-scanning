package com.hhg.callgraph.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FieldAccessIndex")
class FieldAccessIndexTest {

    private FieldAccessIndex index;
    private FieldReference priceField;
    private FieldReference nameField;
    private MethodReference readerMethod;
    private MethodReference writerMethod;
    private MethodReference anotherReader;

    @BeforeEach
    void setUp() {
        index = new FieldAccessIndex();
        priceField    = new FieldReference("com/hhg/entity/Product", "price", "Ljava/math/BigDecimal;");
        nameField     = new FieldReference("com/hhg/entity/Product", "name",  "Ljava/lang/String;");
        readerMethod  = new MethodReference("com/hhg/service/ProductService", "getPrice",       "()Ljava/math/BigDecimal;");
        writerMethod  = new MethodReference("com/hhg/service/ProductService", "setPrice",       "(Ljava/math/BigDecimal;)V");
        anotherReader = new MethodReference("com/hhg/service/OrderService",   "calculateTotal", "()Ljava/math/BigDecimal;");
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Empty index")
    class EmptyIndex {

        @Test
        @DisplayName("getReaders returns empty set for unknown field")
        void getReadersEmpty() {
            assertTrue(index.getReaders(priceField).isEmpty());
        }

        @Test
        @DisplayName("getWriters returns empty set for unknown field")
        void getWritersEmpty() {
            assertTrue(index.getWriters(priceField).isEmpty());
        }

        @Test
        @DisplayName("getAccessors returns empty set for unknown field")
        void getAccessorsEmpty() {
            assertTrue(index.getAccessors(priceField).isEmpty());
        }

        @Test
        @DisplayName("getAllFields returns empty set")
        void getAllFieldsEmpty() {
            assertTrue(index.getAllFields().isEmpty());
        }

        @Test
        @DisplayName("findByName returns empty set when nothing indexed")
        void findByNameEmpty() {
            assertTrue(index.findByName("com/hhg/entity/Product", "price").isEmpty());
        }
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Read tracking")
    class ReadTracking {

        @Test
        @DisplayName("addRead populates getReaders")
        void addReadPopulatesGetReaders() {
            index.addRead(readerMethod, priceField);
            assertTrue(index.getReaders(priceField).contains(readerMethod));
        }

        @Test
        @DisplayName("addRead does not affect getWriters")
        void addReadDoesNotPopulateGetWriters() {
            index.addRead(readerMethod, priceField);
            assertTrue(index.getWriters(priceField).isEmpty());
        }

        @Test
        @DisplayName("multiple readers for the same field are all tracked")
        void multipleReadersTracked() {
            index.addRead(readerMethod,  priceField);
            index.addRead(anotherReader, priceField);
            Set<MethodReference> readers = index.getReaders(priceField);
            assertEquals(2, readers.size());
            assertTrue(readers.contains(readerMethod));
            assertTrue(readers.contains(anotherReader));
        }

        @Test
        @DisplayName("reader of one field does not appear for another field")
        void readersAreFieldIsolated() {
            index.addRead(readerMethod, priceField);
            assertTrue(index.getReaders(nameField).isEmpty());
        }
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Write tracking")
    class WriteTracking {

        @Test
        @DisplayName("addWrite populates getWriters")
        void addWritePopulatesGetWriters() {
            index.addWrite(writerMethod, priceField);
            assertTrue(index.getWriters(priceField).contains(writerMethod));
        }

        @Test
        @DisplayName("addWrite does not affect getReaders")
        void addWriteDoesNotPopulateGetReaders() {
            index.addWrite(writerMethod, priceField);
            assertTrue(index.getReaders(priceField).isEmpty());
        }

        @Test
        @DisplayName("writer of one field does not appear for another field")
        void writersAreFieldIsolated() {
            index.addWrite(writerMethod, priceField);
            assertTrue(index.getWriters(nameField).isEmpty());
        }
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("getAccessors — union of readers and writers")
    class AccessorsUnion {

        @Test
        @DisplayName("getAccessors contains both reader and writer")
        void accessorsIsUnion() {
            index.addRead(readerMethod,  priceField);
            index.addWrite(writerMethod, priceField);
            Set<MethodReference> accessors = index.getAccessors(priceField);
            assertEquals(2, accessors.size());
            assertTrue(accessors.contains(readerMethod));
            assertTrue(accessors.contains(writerMethod));
        }

        @Test
        @DisplayName("getAccessors with only readers")
        void accessorsOnlyReaders() {
            index.addRead(readerMethod,  priceField);
            index.addRead(anotherReader, priceField);
            assertEquals(2, index.getAccessors(priceField).size());
        }

        @Test
        @DisplayName("same method recorded as both reader and writer appears once")
        void sameMethodBothReadAndWrite() {
            index.addRead(readerMethod,  priceField);
            index.addWrite(readerMethod, priceField);
            assertEquals(1, index.getAccessors(priceField).size());
        }
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("getAllFields")
    class GetAllFields {

        @Test
        @DisplayName("returns all fields that have reads or writes")
        void includesBothReadAndWriteFields() {
            index.addRead(readerMethod,  priceField);
            index.addWrite(writerMethod, nameField);
            Set<FieldReference> all = index.getAllFields();
            assertEquals(2, all.size());
            assertTrue(all.contains(priceField));
            assertTrue(all.contains(nameField));
        }

        @Test
        @DisplayName("field appears only once even if it has both reads and writes")
        void noDuplicateFields() {
            index.addRead(readerMethod,  priceField);
            index.addWrite(writerMethod, priceField);
            assertEquals(1, index.getAllFields().size());
        }

        @Test
        @DisplayName("getAllFields result is unmodifiable")
        void getAllFieldsIsUnmodifiable() {
            index.addRead(readerMethod, priceField);
            assertThrows(UnsupportedOperationException.class,
                    () -> index.getAllFields().add(nameField));
        }
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("findByName")
    class FindByName {

        @Test
        @DisplayName("finds field when accessed by reader")
        void findsByReader() {
            index.addRead(readerMethod, priceField);
            Set<FieldReference> found = index.findByName("com/hhg/entity/Product", "price");
            assertEquals(1, found.size());
            assertTrue(found.contains(priceField));
        }

        @Test
        @DisplayName("finds field when accessed by writer")
        void findsByWriter() {
            index.addWrite(writerMethod, priceField);
            Set<FieldReference> found = index.findByName("com/hhg/entity/Product", "price");
            assertEquals(1, found.size());
        }

        @Test
        @DisplayName("returns empty for wrong class name")
        void wrongClassReturnsEmpty() {
            index.addRead(readerMethod, priceField);
            assertTrue(index.findByName("com/hhg/entity/Other", "price").isEmpty());
        }

        @Test
        @DisplayName("returns empty for wrong field name")
        void wrongFieldNameReturnsEmpty() {
            index.addRead(readerMethod, priceField);
            assertTrue(index.findByName("com/hhg/entity/Product", "cost").isEmpty());
        }

        @Test
        @DisplayName("findByName result is unmodifiable")
        void findByNameIsUnmodifiable() {
            index.addRead(readerMethod, priceField);
            Set<FieldReference> found = index.findByName("com/hhg/entity/Product", "price");
            assertThrows(UnsupportedOperationException.class,
                    () -> found.add(nameField));
        }
    }

    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Unmodifiable results")
    class UnmodifiableResults {

        @Test
        @DisplayName("getReaders result is unmodifiable")
        void getReadersIsUnmodifiable() {
            index.addRead(readerMethod, priceField);
            assertThrows(UnsupportedOperationException.class,
                    () -> index.getReaders(priceField).add(writerMethod));
        }

        @Test
        @DisplayName("getWriters result is unmodifiable")
        void getWritersIsUnmodifiable() {
            index.addWrite(writerMethod, priceField);
            assertThrows(UnsupportedOperationException.class,
                    () -> index.getWriters(priceField).add(readerMethod));
        }

        @Test
        @DisplayName("getAccessors result is unmodifiable")
        void getAccessorsIsUnmodifiable() {
            index.addRead(readerMethod, priceField);
            assertThrows(UnsupportedOperationException.class,
                    () -> index.getAccessors(priceField).add(writerMethod));
        }
    }
}
