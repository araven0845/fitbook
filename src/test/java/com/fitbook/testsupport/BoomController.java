package com.fitbook.testsupport;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that blows up, to prove unexpected errors never leak details to the client. */
@RestController
public class BoomController {

    @GetMapping("/test-only/boom")
    public String boom() {
        throw new IllegalStateException("secret internal detail: jdbc:sqlite:/private/path");
    }
}
