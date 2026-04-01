package kz.courier.logisticsservice.exception;

import java.util.UUID;

public class AssignmentNotFoundException extends RuntimeException {
  public AssignmentNotFoundException(UUID id) {
    super("Assignment not found: " + id);
  }
}







