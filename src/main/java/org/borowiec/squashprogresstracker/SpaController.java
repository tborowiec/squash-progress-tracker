package org.borowiec.squashprogresstracker;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({"/login", "/register", "/history", "/matches/**"})
    public String spa() {
        return "forward:/index.html";
    }
}
