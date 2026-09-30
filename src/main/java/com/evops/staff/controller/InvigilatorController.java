package com.evops.staff.controller;

import com.evops.common.ApiResponse;
import com.evops.staff.dto.InvigilatorRequest;
import com.evops.staff.entity.Invigilator;
import com.evops.staff.service.InvigilatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 监考人员花名册（兼职老师）。
 */
@RestController
@RequestMapping("/api/invigilators")
public class InvigilatorController {

    private final InvigilatorService invigilatorService;

    public InvigilatorController(InvigilatorService invigilatorService) {
        this.invigilatorService = invigilatorService;
    }

    @PostMapping
    public ApiResponse<Invigilator> create(@Valid @RequestBody InvigilatorRequest request) {
        return ApiResponse.ok(invigilatorService.create(request));
    }

    @GetMapping
    public ApiResponse<List<Invigilator>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(invigilatorService.list(status, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Invigilator> get(@PathVariable Long id) {
        return ApiResponse.ok(invigilatorService.get(id));
    }
}
