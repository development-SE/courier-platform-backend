package kz.courier.logisticsservice.controller;

import kz.courier.logisticsservice.dto.LogisticsDebugDto;
import kz.courier.logisticsservice.dto.LogisticsDto;
import kz.courier.logisticsservice.service.CapacityAwareAssignmentService;
import kz.courier.logisticsservice.service.LogisticsDebugService;
import kz.courier.logisticsservice.service.LogisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/debug")
@ConditionalOnProperty(name = "logistics.debug.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LogisticsDebugController {

    private final LogisticsDebugService debugService;
    private final CapacityAwareAssignmentService capacityAwareAssignmentService;
    private final LogisticsService logisticsService;

    @GetMapping("/assignment-map")
    public ResponseEntity<LogisticsDebugDto.AssignmentMapSnapshotResponse> getAssignmentMap(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String scenario,
            @RequestParam(defaultValue = "ASTANA") String cityScope,
            @RequestParam(required = false) Double customMinLat,
            @RequestParam(required = false) Double customMinLng,
            @RequestParam(required = false) Double customMaxLat,
            @RequestParam(required = false) Double customMaxLng,
            @RequestParam(defaultValue = "200") int maxCouriers,
            @RequestParam(defaultValue = "200") int maxRoutes,
            @RequestParam(defaultValue = "500") int maxAssignments) {
        
        return ResponseEntity.ok(debugService.getAssignmentMap(
                orderId, scenario, cityScope, customMinLat, customMinLng, customMaxLat, customMaxLng,
                maxCouriers, maxRoutes, maxAssignments));
    }

    @PostMapping("/assignment-preview/{orderId}")
    public ResponseEntity<LogisticsDebugDto.CandidatePreviewResponse> previewAssignment(
            @PathVariable UUID orderId) {
        
        return ResponseEntity.ok(capacityAwareAssignmentService.previewCandidates(orderId));
    }

    @PostMapping("/assign/{orderId}")
    public ResponseEntity<LogisticsDebugDto.AssignmentMapSnapshotResponse> triggerRealAssignment(
            @PathVariable UUID orderId) {
        
        // Trigger real assignment
        logisticsService.autoAssign(orderId);
        
        // Return updated map snapshot
        return ResponseEntity.ok(debugService.getAssignmentMap(
                orderId, null, "ASTANA", null, null, null, null, 200, 200, 500));
    }

    @PostMapping("/scenarios/{scenarioName}")
    public ResponseEntity<Void> seedScenario(@PathVariable String scenarioName) {
        debugService.seedScenario(scenarioName);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/scenarios/current")
    public ResponseEntity<Void> resetScenarios() {
        debugService.resetDebugData();
        return ResponseEntity.ok().build();
    }

    @PutMapping("/couriers/{courierId}/location")
    public ResponseEntity<Void> forceCourierLocation(
            @PathVariable UUID courierId,
            @RequestBody kz.courier.logisticsservice.dto.LogisticsDto.UpdateLocationRequest req) {
        debugService.forceCourierLocation(courierId, req.latitude(), req.longitude(), req.isOnline());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/simulation/start")
    public ResponseEntity<LogisticsDebugDto.SimulationStatusResponse> startSimulation(
            @RequestBody(required = false) LogisticsDebugDto.SimulationStartRequest request) {
        return ResponseEntity.ok(debugService.startSimulation(request));
    }

    @PostMapping("/simulation/step")
    public ResponseEntity<LogisticsDebugDto.SimulationStatusResponse> stepSimulation() {
        return ResponseEntity.ok(debugService.stepSimulation());
    }

    @PostMapping("/simulation/tick")
    public ResponseEntity<LogisticsDebugDto.SimulationStatusResponse> tickSimulationAlias() {
        return ResponseEntity.ok(debugService.stepSimulation());
    }

    @PostMapping("/simulation/stop")
    public ResponseEntity<LogisticsDebugDto.SimulationStatusResponse> stopSimulation() {
        return ResponseEntity.ok(debugService.stopSimulation());
    }

    @GetMapping("/simulation/status")
    public ResponseEntity<LogisticsDebugDto.SimulationStatusResponse> getSimulationStatus() {
        return ResponseEntity.ok(debugService.getSimulationStatus());
    }
}
