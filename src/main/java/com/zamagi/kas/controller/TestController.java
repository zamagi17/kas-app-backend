package com.zamagi.kas.controller;

import com.zamagi.kas.service.ReportScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private ReportScheduler reportScheduler;

    @GetMapping("/send-email")
    public String testEmail() {
        reportScheduler.sendMonthlyReports();
        return "Email triggered!";
    }
}