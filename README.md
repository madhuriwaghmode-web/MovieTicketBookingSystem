# Movie Ticket Booking System

A beginner-friendly Java mini project using Spring Boot, JDBC and an H2 in-memory database. Payment is simulated; no real payment information is collected.

## Requirements
- JDK 17+
- Maven 3.8+

## Run
1. Extract the ZIP and open the folder in IntelliJ IDEA.
2. Run `MovieTicketApplication.java`, or run `mvn spring-boot:run` in this folder.
3. API base URL: `http://localhost:8080`.
4. H2 console: `http://localhost:8080/h2-console`; JDBC URL `jdbc:h2:mem:moviedb`, username `sa`, empty password.

The database resets when the app restarts.

## Endpoints
- `GET /api/movies`
- `GET /api/shows` (optional filters: `?movieId=1`, `?city=Pune`, `?date=YYYY-MM-DD`)
- `GET /api/shows/1/seats`
- `POST /api/bookings`
- `GET /api/bookings/1`
- `PATCH /api/bookings/1/cancel`

Example JSON for `POST /api/bookings`:
```json
{
  "customerName": "Ayesha",
  "showId": 1,
  "seatIds": [1, 2]
}
```
Set `Content-Type: application/json`.

## Test ideas
1. List movies and shows.
2. Check seats for show 1.
3. Book seats 1 and 2.
4. Try booking seat 1 again; it should fail with a conflict response.
5. Try a nonexistent seat ID.
6. Cancel a booking and check that its seats become available again.

The `UNIQUE(show_id, seat_id)` database constraint prevents the same seat being booked twice for a show. Payment is simulated and this is a backend API, not a full website.
