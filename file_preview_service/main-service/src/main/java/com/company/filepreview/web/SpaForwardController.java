package com.company.filepreview.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards non-API, non-asset routes to the SPA entry point so client-side routing
 * works on deep links / refreshes. API paths are excluded by the path patterns.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/{path:^(?!api|assets|actuator|monaco)[^\\.]*}",
            "/{path:^(?!api|assets|actuator|monaco)[^\\.]*}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
