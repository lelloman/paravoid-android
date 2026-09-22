package com.lelloman.paravoidandroid.contract;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class StrictJsonTest {
    private Object parse(String value) throws Exception { return StrictJson.parse(value.getBytes(StandardCharsets.UTF_8), 65536); }
    @Test public void rejectsAmbiguousNumbersDuplicatesUnicodeAndTrailingContent() {
        for (String value : Arrays.asList("{\"a\":null,\"a\":1}", "[1,]", "{\"a\":1,}", "01", "-0", "-1", "1.0", "1e0",
                "9007199254740992", "[true false]", "null null", "\"\\ud800\"", "\"\\udc00\"", "\"\n\"", "\"\\u１２３４\""))
            assertThrows(value, ContractException.class, () -> parse(value));
        assertThrows(ContractException.class, () -> StrictJson.parse(new byte[]{(byte)0xc0, (byte)0xaf}, 10));
        assertThrows(ContractException.class, () -> parse("[".repeat(34) + "0" + "]".repeat(34)));
    }
    @Test public void canonicalizesUtf16OrderEscapesAndSafeIntegers() throws Exception {
        String text = "{\"z\":9007199254740991,\"a\":\"é😀\\n\\u0000\"}";
        assertEquals("{\"a\":\"é😀\\n\\u0000\",\"z\":9007199254740991}",
            new String(StrictJson.canonical(parse(text)), StandardCharsets.UTF_8));
        Map<String,Object> ordered = new HashMap<>(); ordered.put("\ufffd", 1); ordered.put("😀", 2);
        assertEquals("{\"😀\":2,\"�\":1}", new String(StrictJson.canonical(ordered), StandardCharsets.UTF_8));
        assertThrows(ContractException.class, () -> StrictJson.canonical(1.5));
    }
}
