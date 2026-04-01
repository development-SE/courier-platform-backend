package kz.courier.logisticsservice.entity;

import lombok.Getter;

/**
 * Статусы жизненного цикла назначения курьера на заказ.
 *
 * Переходы между статусами строго контролируются в сервисе
 * (см. AssignmentStatusTransitionValidator).
 */
@Getter
public enum AssignmentStatus {

    // ── Начальные статусы ─────────────────────────────────────
    PENDING("Ожидает назначения"),           // Система только что создала assignment
    ASSIGNED("Назначен курьеру"),            // Курьер получил уведомление

    // ── Курьер принял / начал выполнение ─────────────────────
    ACCEPTED("Курьер принял заказ"),         // Курьер нажал "Принять"
    REJECTED("Курьер отказался"),            // Курьер нажал "Отказаться"

    // ── Этапы выполнения ─────────────────────────────────────
    PICKED_UP("Забран у партнёра"),          // Курьер забрал заказ у ресторана/магазина
    IN_TRANSIT("В пути к клиенту"),          // Курьер едет к клиенту

    // ── Завершающие статусы (terminal) ───────────────────────
    DELIVERED("Доставлен клиенту"),          // Заказ успешно доставлен
    CANCELLED("Отменён"),                    // Отменён администратором или системой
    FAILED("Не выполнен"),                   // Не удалось выполнить (курьер не доехал и т.д.)

    // ── Дополнительные статусы (если нужно) ──────────────────
    // ON_HOLD("Приостановлен"),             // Можно добавить позже
    // RETURNED("Возвращён на склад");

    ;

    private final String description;

    AssignmentStatus(String description) {
        this.description = description;
    }

    /**
     * Проверяет, является ли статус финальным (терминальным).
     * После терминального статуса изменение невозможно.
     */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == FAILED;
    }

    /**
     * Проверяет, является ли статус "активным" (курьер ещё работает над заказом).
     */
    public boolean isActive() {
        return !isTerminal() && this != REJECTED;
    }

    /**
     * Можно ли перейти из текущего статуса в новый.
     * (Простая версия — можно вынести в отдельный TransitionValidator)
     */
    public boolean canTransitionTo(AssignmentStatus newStatus) {
        if (isTerminal()) {
            return false; // Из финального статуса переходить нельзя
        }

        return switch (this) {
            case PENDING   -> newStatus == ASSIGNED || newStatus == CANCELLED;
            case ASSIGNED  -> newStatus == ACCEPTED || newStatus == REJECTED || newStatus == CANCELLED;
            case ACCEPTED  -> newStatus == PICKED_UP || newStatus == CANCELLED;
            case PICKED_UP -> newStatus == IN_TRANSIT || newStatus == CANCELLED;
            case IN_TRANSIT-> newStatus == DELIVERED || newStatus == FAILED || newStatus == CANCELLED;
            case REJECTED, DELIVERED, CANCELLED, FAILED -> false;
        };
    }
}