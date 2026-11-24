# Heartlink — Runbook Nâng Cấp Database Mới / Cũ & Kiểm Tra Schema Drift (T04)

## 1. Tổng quan & Nguyên tắc An toàn

Từ Phase 2 (T03–T04), toàn bộ vòng đời schema PostgreSQL 16 của Heartlink được quản lý bằng **Flyway** kết hợp với:
1. **`spring.jpa.hibernate.ddl-auto=validate`** trên mọi profile vận hành (`default`, `dev`, `docker`, `release`) — Hibernate tuyệt đối không tự sửa bảng/cột lúc khởi động.
2. **`SchemaDriftValidator`** — kiểm tra metadata chỉ đọc (`information_schema`, `pg_constraint`, `pg_indexes`) lúc khởi động để bắt các sai lệch mà Hibernate `validate` bỏ sót (thiếu FK, thiếu `UNIQUE`, thiếu `NOT NULL`, thiếu `CHECK`, thiếu index phụ hoặc trùng index).
3. **`spring.flyway.baseline-on-migrate=false`** — cấm tự động baseline mù trên DB khác rỗng chưa có `flyway_schema_history`.
4. **Data Audit trước `CHECK` (`V2__audit_and_add_pair_self_checks.sql`)** — chỉ thêm `chk_swipes_no_self` (`swiper_id <> target_id`) và `chk_matches_canonical_pair` (`user_a_id < user_b_id`) sau khi kiểm toán 0 dòng vi phạm; nếu có dữ liệu bẩn, transaction rollback hoàn toàn, **không tự ý sửa hoặc xóa dòng dữ liệu nào**.
5. **Seed Guard hai lớp** — chặn `seed/seed.sql` (`TRUNCATE TABLE users RESTART IDENTITY CASCADE`) ở cả mức transaction PostgreSQL lẫn Spring profile (`release`, `prod`, `production`, `staging`).

---

## 2. Danh mục Datasource Profiles

| Profile | File cấu hình | Mục đích | `ddl-auto` | Demo Seed (`TRUNCATE`) |
|---|---|---|---|---|
| *(default)* | `application.properties` | Chạy local chuẩn nối `localhost:5432/heartlink` | `validate` | Chặn (`false`) |
| `dev` | `application-dev.properties` | Phát triển cục bộ; cho phép bật demo seed có chủ đích qua `HEARTLINK_DEMO_SEED=true` | `validate` | Chỉ khi `HEARTLINK_DEMO_SEED=true` |
| `docker` | `application-docker.properties` | Chạy trong Docker Compose (`postgres:5432/heartlink`) | `validate` | Chặn (`false`) |
| `release` | `application-release.properties` | Môi trường release/staging/production (bắt buộc truyền `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`) | `validate` (cố định) | Cấm cứng ở cả Spring & SQL |

---

## 3. Quy trình Triển khai trên Database Mới (DB Rỗng)

Áp dụng cho môi trường CI, dev mới tạo container, hoặc môi trường staging/production khởi tạo mới hoàn toàn.

### Bước 1: Tạo database rỗng
```bash
psql -h <DB_HOST> -U <DB_ADMIN> -c "CREATE DATABASE heartlink;"
# Khuyến nghị cho môi trường release/prod: khóa cứng cờ môi trường ở cấp DB để chặn mọi thao tác chạy nhầm seed.sql
psql -h <DB_HOST> -U <DB_ADMIN> -d heartlink -c "ALTER DATABASE heartlink SET heartlink.env = 'release';"
```

### Bước 2: Khởi động ứng dụng
```bash
export SPRING_PROFILES_ACTIVE=release
export DB_URL="jdbc:postgresql://<DB_HOST>:5432/heartlink"
export DB_USERNAME="<APP_DB_USER>"
export DB_PASSWORD="<APP_DB_PASSWORD>"
export JWT_SECRET="<AT_LEAST_32_BYTES_RANDOM_SECRET>"

java -jar target/heartlink-0.0.1-SNAPSHOT.jar
```
- Flyway tự động chạy tuần tự `V1__baseline_schema.sql` → `V2__audit_and_add_pair_self_checks.sql`.
- Hibernate `ddl-auto=validate` và `SchemaDriftValidator` xác nhận đủ 6 bảng, 33 cột, 6 PK, 5 UNIQUE, 9 FK, 5 CHECK, 4 index phụ.

---

## 4. Quy trình Nâng cấp Database Hiện Có từ Phase 1 (Snapshot Cũ chưa có Flyway)

> **CẢNH BÁO:** Không bật `spring.flyway.baseline-on-migrate=true` (ứng dụng sẽ từ chối khởi động). Tuyệt đối không sửa/xóa trực tiếp trên DB đang phục vụ trước khi tạo bản sao lưu.

### Bước 1: Sao lưu (Backup) toàn bộ DB hiện tại
```bash
pg_dump -Fc -h <DB_HOST> -U <DB_USER> -d heartlink -f heartlink_pre_t04_$(date +%Y%m%d_%H%M%S).dump
```

### Bước 2: Chạy truy vấn kiểm toán trước nâng cấp (Read-Only Pre-Check)
Chạy truy vấn chỉ đọc sau trên DB (hoặc bản dựng từ bản dump) để kiểm tra dữ liệu cặp/self trước khi chạy `V2`:
```sql
-- Phải trả về 0 dòng ở cả hai truy vấn
SELECT id, swiper_id, target_id FROM swipes WHERE swiper_id = target_id;
SELECT id, user_a_id, user_b_id FROM matches WHERE user_a_id >= user_b_id;

-- Kiểm tra số lượng bản ghi trước nâng cấp để đối chiếu sau nâng cấp
SELECT 'users' AS tbl, count(*), min(id), max(id) FROM users
UNION ALL SELECT 'refresh_tokens', count(*), min(id), max(id) FROM refresh_tokens
UNION ALL SELECT 'swipes', count(*), min(id), max(id) FROM swipes
UNION ALL SELECT 'matches', count(*), min(id), max(id) FROM matches
UNION ALL SELECT 'messages', count(*), min(id), max(id) FROM messages
UNION ALL SELECT 'notifications', count(*), min(id), max(id) FROM notifications;
```
- Nếu phát hiện dòng vi phạm `swiper_id = target_id` hoặc `user_a_id >= user_b_id`, **dừng nâng cấp** và báo đội nghiệp vụ xử lý thủ công theo biên bản (migration `V2` cũng sẽ tự chặn và rollback nếu còn dòng vi phạm).

### Bước 3: Chạy nâng cấp một lần với `HEARTLINK_GUARDED_BASELINE=true`
```bash
export SPRING_PROFILES_ACTIVE=release
export HEARTLINK_GUARDED_BASELINE=true
export DB_URL="jdbc:postgresql://<DB_HOST>:5432/heartlink"
export DB_USERNAME="<APP_DB_USER>"
export DB_PASSWORD="<APP_DB_PASSWORD>"
export JWT_SECRET="<AT_LEAST_32_BYTES_RANDOM_SECRET>"

java -jar target/heartlink-0.0.1-SNAPSHOT.jar
```
Luồng thực thi tự động có bảo vệ:
1. `SchemaDriftValidator.verifyPhase1BaselineContract` đối chiếu toàn bộ 6 bảng, cột, kiểu dữ liệu, `NOT NULL`, PK, UNIQUE, 9 FK (chấp nhận cả tên FK dạng hash `fk...` do Hibernate Phase 1 sinh ra) và 4 index phụ. Nếu lệch → **dừng ngay với `[SCHEMA-DRIFT]` trước khi tạo `flyway_schema_history`**.
2. Ghi nhận `flyway.baseline()` ở `version = 1`.
3. Thực thi `V2__audit_and_add_pair_self_checks.sql` trong một transaction: kiểm toán dữ liệu → thêm `chk_swipes_no_self` và `chk_matches_canonical_pair` → nâng schema lên `version = 2`.
4. Chạy Hibernate `ddl-auto=validate` và `SchemaDriftValidator.verifyPostMigrationContract`.

### Bước 4: Đối chiếu sau nâng cấp & tắt cờ `HEARTLINK_GUARDED_BASELINE`
1. Chạy lại câu lệnh đếm dòng / `min(id)` / `max(id)` ở Bước 2 và kiểm tra `flyway_schema_history`:
   ```sql
   SELECT installed_rank, version, description, type, success FROM flyway_schema_history ORDER BY installed_rank;
   ```
   Kết quả kỳ vọng: dòng `1` (`<< Flyway Baseline >>`, `success = t`) và dòng `2` (`audit and add pair self checks`, `success = t`); toàn bộ `count(*)`, `id`, FK, index giữ nguyên 100%.
2. Gỡ biến `HEARTLINK_GUARDED_BASELINE` (trở về mặc định `false`) cho mọi lần khởi động tiếp theo.

---

## 5. Xử lý Lỗi Khởi động (Troubleshooting)

| Thông báo lỗi khi khởi động | Nguyên nhân | Cách xử lý |
|---|---|---|
| `Found non-empty schema(s) "public" but no schema history table` | DB đã có bảng từ Phase 1 nhưng chưa chạy quy trình nâng cấp Mục 4 | Thực hiện Mục 4 (backup → pre-check → bật một lần `HEARTLINK_GUARDED_BASELINE=true`). |
| `[SCHEMA-DRIFT] Pre-baseline Phase 1 snapshot audit failed...` | Schema DB cũ bị thiếu bảng/cột/FK/index hoặc sai kiểu dữ liệu so với chuẩn `V1` | Đọc danh sách `violation(s)` trong log, bổ sung index/constraint còn thiếu trên bản staging trước khi chạy lại. |
| `[T04-DATA-AUDIT] Cannot add chk_...` | Tồn tại dòng tự swipe (`swiper_id = target_id`) hoặc match ngược thứ tự (`user_a_id >= user_b_id`) | Kiểm tra ID dòng được in trong log; xử lý dữ liệu theo quy trình phê duyệt rồi mới chạy lại nâng cấp. |
| `[SCHEMA-DRIFT] Post-migration startup validation failed...` hoặc `SchemaManagementException` | Ai đó sửa tay DB sau khi migrate (xóa index, xóa constraint, đổi kiểu/nullability cột) | Khôi phục đối tượng schema bị sửa tay hoặc viết migration `V3+` hợp lệ. |
| `[SEED-GUARD] ...` | Cố bật `app.seed.demo-enabled=true` hoặc chạy `seed/seed.sql` trên môi trường không được phép | Tắt ngay cấu hình seed demo; chỉ nạp dữ liệu mẫu trên DB `dev` cục bộ. |

---

## 6. Cách chạy Demo Seed trên Local Dev (Chỉ dành cho môi trường phát triển)

Trên môi trường dev cục bộ (tuyệt đối không dùng cho staging/release/production):

- **Cách 1 — Qua `psql`:**
  ```bash
  docker compose exec -T -e PGOPTIONS="-c heartlink.allow_demo_seed=true" postgres \
    psql -v ON_ERROR_STOP=1 -U postgres -d heartlink < seed/seed.sql
  ```
- **Cách 2 — Qua profile `dev` của Spring Boot:**
  ```bash
  SPRING_PROFILES_ACTIVE=dev HEARTLINK_DEMO_SEED=true ./mvnw spring-boot:run
  ```

