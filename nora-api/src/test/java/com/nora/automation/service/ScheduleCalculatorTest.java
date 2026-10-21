package com.nora.automation.service;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ScheduleCalculatorTest {
    @Test
    void parsesSchedulesAndAdvancesDailyAndWeekly() throws Exception {
        var mapper = new ObjectMapper();
        for (String time : new String[]{"9:00", "09:00", "23:59"}) {
            var schedule = ScheduleCalculator.parse(mapper.readTree(
                    "{\"frequency\":\"daily\",\"localTime\":\"" + time + "\"}"));
            ScheduleCalculator.nextAfter(schedule, LocalDateTime.of(2026, 10, 11, 8, 0));
        }
        var weekly = ScheduleCalculator.parse(mapper.readTree(
                "{\"frequency\":\"weekly\",\"localTime\":\"9:00\",\"dayOfWeek\":1,\"timezone\":\"America/New_York\"}"));
        ScheduleCalculator.nextAfter(weekly, LocalDateTime.of(2026, 10, 11, 8, 0));
        for (String time : new String[]{"24:00", "25:00", "9:60", "invalid"}) {
            ScheduleCalculator.parse(mapper.readTree(
                    "{\"frequency\":\"daily\",\"localTime\":\"" + time + "\"}"));
        }
        ScheduleCalculator.parse(mapper.readTree("{\"frequency\":\"weekly\",\"localTime\":\"9:00\",\"dayOfWeek\":0}"));
        ScheduleCalculator.parse(mapper.readTree("{\"frequency\":\"daily\",\"localTime\":\"9:00\",\"timezone\":\"invalid\"}"));
    }
}
