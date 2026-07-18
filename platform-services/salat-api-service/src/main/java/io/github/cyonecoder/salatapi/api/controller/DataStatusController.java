package io.github.cyonecoder.salatapi.api.controller;

import io.github.cyonecoder.salatapi.api.dto.response.DataStatusResponse;
import io.github.cyonecoder.salatapi.api.dto.response.ServiceInfoResponse;
import io.github.cyonecoder.salatapi.application.DataStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service information (spec 23.1) and operational data status (spec 23.15). */
@RestController
@RequestMapping("/api/v1")
public class DataStatusController {

    private final DataStatusService dataStatus;

    public DataStatusController(DataStatusService dataStatus) {
        this.dataStatus = dataStatus;
    }

    @GetMapping("/info")
    public ServiceInfoResponse info() {
        return dataStatus.info();
    }

    @GetMapping("/data-status")
    public DataStatusResponse dataStatus() {
        return dataStatus.dataStatus();
    }
}
