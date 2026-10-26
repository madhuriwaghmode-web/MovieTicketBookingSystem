package com.mini.movieticket;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class BookingController {
    private final JdbcTemplate jdbc;

    public BookingController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/movies")
    public List<Map<String, Object>> getMovies() {
        return jdbc.queryForList("SELECT * FROM movie ORDER BY id");
    }

    @GetMapping("/shows")
    public List<Map<String, Object>> getShows(
            @RequestParam(required = false) Long movieId,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String date) {
        StringBuilder sql = new StringBuilder("SELECT sh.id AS show_id, m.title AS movie, t.name AS theatre, t.city, sc.screen_name, sh.show_date, sh.show_time, sh.ticket_price FROM show_schedule sh JOIN movie m ON sh.movie_id=m.id JOIN screen sc ON sh.screen_id=sc.id JOIN theatre t ON sc.theatre_id=t.id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (movieId != null) { sql.append(" AND sh.movie_id=?"); args.add(movieId); }
        if (city != null && !city.isBlank()) { sql.append(" AND LOWER(t.city)=LOWER(?)"); args.add(city); }
        if (date != null && !date.isBlank()) { sql.append(" AND sh.show_date=?"); args.add(date); }
        sql.append(" ORDER BY sh.show_date, sh.show_time");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/shows/{id}/seats")
    public List<Map<String, Object>> getSeats(@PathVariable long id) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM show_schedule WHERE id=?", Integer.class, id);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Show not found");
        return jdbc.queryForList("SELECT s.id AS seat_id, s.seat_number, CASE WHEN bs.id IS NULL THEN 'AVAILABLE' ELSE 'BOOKED' END AS seat_status FROM show_schedule sh JOIN seat s ON s.screen_id=sh.screen_id LEFT JOIN booking_seat bs ON bs.show_id=sh.id AND bs.seat_id=s.id WHERE sh.id=? ORDER BY s.seat_number", id);
    }

    @PostMapping("/bookings")
    @Transactional
    public ResponseEntity<Map<String, Object>> createBooking(@RequestBody BookingRequest request) {
        if (request.customerName == null || request.customerName.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name is required");
        if (request.seatIds == null || request.seatIds.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one seat");
        if (new HashSet<>(request.seatIds).size() != request.seatIds.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate seat IDs were provided");

        List<Map<String, Object>> shows = jdbc.queryForList("SELECT id, screen_id, ticket_price FROM show_schedule WHERE id=?", request.showId);
        if (shows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Show not found");
        long screenId = ((Number) shows.get(0).get("SCREEN_ID")).longValue();
        BigDecimal price = (BigDecimal) shows.get(0).get("TICKET_PRICE");

        for (Long seatId : request.seatIds) {
            List<Map<String, Object>> seats = jdbc.queryForList("SELECT id FROM seat WHERE id=? AND screen_id=?", seatId, screenId);
            if (seats.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seat " + seatId + " does not belong to this show");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM booking_seat WHERE show_id=? AND seat_id=?", Integer.class, request.showId, seatId);
            if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat " + seatId + " is already booked");
        }

        String reference = "MT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO booking(booking_reference, customer_name, show_id, status) VALUES(?,?,?,'CONFIRMED')", reference, request.customerName.trim(), request.showId);
        Long bookingId = jdbc.queryForObject("SELECT id FROM booking WHERE booking_reference=?", Long.class, reference);
        for (Long seatId : request.seatIds)
            jdbc.update("INSERT INTO booking_seat(booking_id, show_id, seat_id) VALUES(?,?,?)", bookingId, request.showId, seatId);

        BigDecimal total = price.multiply(BigDecimal.valueOf(request.seatIds.size()));
        jdbc.update("INSERT INTO payment_record(booking_id, amount, payment_status) VALUES(?,?,'SIMULATED_SUCCESS')", bookingId, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bookingId", bookingId);
        result.put("bookingReference", reference);
        result.put("customerName", request.customerName.trim());
        result.put("showId", request.showId);
        result.put("seatIds", request.seatIds);
        result.put("totalAmount", total);
        result.put("bookingStatus", "CONFIRMED");
        result.put("paymentStatus", "SIMULATED_SUCCESS");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/bookings/{id}")
    public Map<String, Object> getBooking(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT b.id AS booking_id, b.booking_reference, b.customer_name, b.show_id, b.status AS booking_status, b.created_at, p.amount, p.payment_status FROM booking b LEFT JOIN payment_record p ON p.booking_id=b.id WHERE b.id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("seats", jdbc.queryForList("SELECT s.id AS seat_id, s.seat_number FROM booking_seat bs JOIN seat s ON bs.seat_id=s.id WHERE bs.booking_id=? ORDER BY s.seat_number", id));
        return result;
    }

    @PatchMapping("/bookings/{id}/cancel")
    @Transactional
    public Map<String, Object> cancelBooking(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM booking WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        if ("CANCELLED".equals(rows.get(0).get("STATUS").toString()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking is already cancelled");
        jdbc.update("UPDATE booking SET status='CANCELLED' WHERE id=?", id);
        jdbc.update("DELETE FROM booking_seat WHERE booking_id=?", id);
        jdbc.update("UPDATE payment_record SET payment_status='SIMULATED_REFUND' WHERE booking_id=?", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bookingId", id);
        result.put("bookingStatus", "CANCELLED");
        result.put("message", "Booking cancelled. Seats are available again.");
        return result;
    }

    public static class BookingRequest {
        public String customerName;
        public long showId;
        public List<Long> seatIds;
    }
}
