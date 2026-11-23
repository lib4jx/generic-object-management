# generic-object-management

Thư viện Spring Boot (package `com.msm.core.objects`) cung cấp sẵn cho các service MSM:

- **REST API generic** cho mọi object: filter, lookup, CRUD, bulk, named action, query, conversion.
- **CRUD engine** trên jOOQ, có kiểm tra quyền, xóa mềm, audit, khóa lạc quan, `customValues`.
- **Import CSV/Excel** (qua S3 presigned URL, staging, báo lỗi theo dòng) và **export Excel**.
- **Client gọi service nội bộ** và **client tích hợp hệ thống ngoài** (auth, retry, ghi log tích hợp).

Thư viện xây trên [commons](https://github.com/lib4jx/commons). Các khái niệm metadata, `TypedAttribute`, filter, action/hook được mô tả trong README của commons.

## Cài đặt

Bản phát hành là **git tag** (ví dụ `2.5.3`), build qua [JitPack](https://jitpack.io/#lib4jx/generic-object-management).

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.lib4jx:generic-object-management:2.5.3")
    // Thêm dòng này nếu service dùng trực tiếp type của commons. Phải cùng version với msmCommonVersion của thư viện này
    implementation("com.github.lib4jx:commons:2.4.6")
}
```

Yêu cầu:

- Java 21 và Spring Boot 3.5.x.
- PostgreSQL, có extension `unaccent`.
- Redis, nếu dùng OAuth token cho tích hợp.
- S3 hoặc dịch vụ tương thích S3, nếu dùng import/export.

## Tích hợp vào service

Thư viện tự cấu hình qua `MsmAutoConfiguration`, được nạp bằng `META-INF/spring/...AutoConfiguration.imports`. Service cần làm thêm các việc sau.

### 1. Bật các annotation

```java
@SpringBootApplication
@EnableMethodSecurity   // thiếu: @PreAuthorize trên controller không có tác dụng
@EnableAsync            // thiếu: export chạy đồng bộ ngay trong request
public class OrderServiceApplication { }
```

### 2. Cung cấp bean

Thư viện không tự tạo các bean sau:

- `DSLContext` (jOOQ)
- `EntityManager` (JPA)
- `RestClient.Builder`
- `ObjectMapper`
- `S3Presigner`, bắt buộc cho import/export
- `LettuceConnectionFactory` và `StringRedisTemplate` cho Redis

`S3Client` là tùy chọn; nếu không khai báo, thư viện dùng `S3Client.create()`.

Thư viện **không** gắn header auth hay tenant vào request gọi service khác. Nếu API nội bộ cần các header này, hãy cấu hình interceptor trên `RestClient.Builder`.

### 3. Cấu hình

```yaml
objects:
  executor: { core: 5, max: 20 }
  api-config:
    base-url: https://api.example.com      # bắt buộc khi gọi sang service khác (service không có trong service-urls)
    service-urls:                          # tùy chọn: host nội bộ theo service, lib nối thêm "/<service>"
      user: ${USER_SERVICE_URL:}           # trống (biến không set) thì dùng base-url
      master-data: ${MASTER_DATA_SERVICE_URL:}

aws:
  s3:
    bucket-name: my-bucket
    region: ap-southeast-1
    endpoint:                               # tùy chọn (MinIO, LocalStack...)
    sub-folder: order                       # nên đặt, nếu để trống đường dẫn file sẽ thiếu phần object/ngày
    presigned-url-expiration: 15            # phút

integration:
  connectors:
    my-partner:
      base-url: https://partner.example.com
      auth: { ... }                         # api-key | basic | bearer | oauth2 client-credentials | oauth2 password
      retry: { ... }
      timeout: { connect-ms: 10000, read-ms: 300000 }          # tùy chọn, xem mục "Gọi đối tác bất đồng bộ"
      async: { enabled: false, deadline-ms: 360000, idempotent: false, inline-wait-ms: 0 }   # tùy chọn
```

- Cấu hình import/export (`imports.*`, `exports.*`) có thể đặt trong `classpath:imports/*.yml` và `classpath:exports/*.yml`. Các file này được nạp tự động với **độ ưu tiên cao nhất**, ghi đè cả `application.yml`.
- Nên khai báo `imports.processing` và `exports.processing` ở cấp global.

### 4. Database

Thư viện thêm các JPA entity của nó vào persistence unit của service. Database phải có các bảng:

- `import_job`
- `import_staging`
- `import_error`
- `export_job`
- `data_job`
- `integration_log`
- `object_metadata`, nếu dùng metadata động
- `audit_log_outbox`, nếu bật change log (không phải JPA entity, xem mục Change log)
- `integration_task` (JPA entity từ 2.7.3, object chỉ đọc `integrationtask`): cần khi có connector bật async; service đặt `ddl-auto=validate` thì luôn cần. Script ở mục "Gọi đối tác bất đồng bộ"

Các bảng job kế thừa `SoftDeleteEntity` → `BusinessProcessEntity` → `AuditingEntity`, nên phải có đủ các cột sau:

| Nhóm | Cột |
|---|---|
| Audit | `created_*`, `updated_*` |
| Business process | `team_id`, `org_id`, `workflow_instance_id`, `record_type_id`, `stage_ids`, `approval_status`, `locked_edit` |
| Xóa mềm | `deleted_*`, `is_deleted` |

Nếu thiếu và service đặt `ddl-auto=validate`, ứng dụng sẽ không khởi động được.

### 5. Ghi đè bean

Hầu hết bean có `@ConditionalOnMissingBean`, nên chỉ cần khai báo bean cùng kiểu (hoặc cùng tên) để thay thế. Các bean sau **không** có điều kiện này nên không thay được:

- Hai controller
- `GenericObjectMetadataService`
- `PermissionService`
- `SecurityCheckProvider`
- Các auth provider
- Phần lớn bean import/export

## REST API

Endpoint portal có prefix `/api/v1/cn/portal/generic/objects`. Phiên bản nội bộ có cùng các endpoint, prefix `/api/v1/internal`.

| Method | Path | Mô tả |
|---|---|---|
| POST | `/{obj}/filter` | Filter, phân trang (body `ObjectFilterRequest`) |
| POST | `/{obj}/lookup` | Lookup (dùng quyền LOOKUP) |
| GET | `/{obj}/{id}?returnFields=` | Lấy theo id |
| GET | `/{obj}` | Lấy tất cả |
| POST | `/{obj}` | Tạo mới |
| POST | `/{obj}/bulk` | Tạo nhiều bản ghi |
| PUT | `/{obj}/{id}` | Cập nhật |
| PUT | `/{obj}/bulk` | Cập nhật nhiều bản ghi |
| DELETE | `/{obj}/{id}?version=` | Xóa |
| DELETE | `/{obj}/bulk` | Xóa nhiều bản ghi (body `List<ObjectDeleteRequest>`) |
| PUT | `/{obj}/{id}/{actionName}` | Chạy named action |
| PUT | `/{obj}/bulk/{actionName}` | Chạy named action cho nhiều bản ghi |
| POST | `/query` | Named query (`QueryTemplate`) |
| POST | `/conversion` | Chuyển đổi object |
| POST | `/metadata` | Sinh metadata từ `information_schema` vào `object_metadata` |
| GET | `/schema` | **Chỉ nội bộ.** Mọi object của service kèm `hash` schema |
| GET | `/{obj}/schema` | **Chỉ nội bộ.** Schema chỉ đọc của object (xem [Schema của object](#schema-của-object)) |
| | `/{obj}/imports/...`, `/importjob`, `/exportjob` | Import/export (xem bên dưới) |

- Response trả thẳng `Map`, `List` hoặc `PageResponse`, không có wrapper.
- **API nội bộ không kiểm tra quyền**, nên chỉ được mở trong mạng nội bộ. Các endpoint `/query`, `/metadata` và import cũng chưa có `@PreAuthorize`.

## Tùy biến nghiệp vụ

Mỗi request được chuyển thành `ActionContext(resource = objectName, action = ...)` rồi chạy qua `ActionExecutor`. Nếu object không có handler riêng, `GenericObjectHandler` (resource `*`) xử lý.

Khi khởi động, `ObjectBeanConfigInitializing` **tự scan mọi bean** để đăng ký handler và hook. Vì vậy chỉ cần khai báo Spring bean:

```java
@Component
public class OrderHandlers {

    // Thay logic tạo mới cho object "order"
    @CreateHandler(resource = "order")
    public Map<String, Object> create(ActionContext<Map<String, Object>> ctx) { ... }

    // Named action: PUT /generic/objects/order/{id}/approve
    @Handler(resource = "order", action = "approve")
    @SecuredPermissionAction(anyOf = ...)
    public Map<String, Object> approve(ActionContext<Map<String, Object>> ctx) { ... }

    // Named query: POST /generic/objects/query với query = "orderSummary"
    @Handler(resource = "query-*", action = "orderSummary")
    public Object orderSummary(ActionContext<?> ctx) { ... }

    // Hook chạy trước khi tạo
    @HookBefore(resource = "order", action = Constants.Action.CREATE)
    public void beforeCreate(ActionContext<Map<String, Object>> ctx) { ... }
}
```

- **Key handler:** viết thường. Có nhiều handler cùng khớp một key thì ném lỗi khi chạy.
- **Named action không có `@SecuredPermissionAction`:** mặc định **được phép** cho mọi user.
- **Strategy theo object:**
  - `AuditStrategy`: cách điền field audit.
  - `CustomValueMappingStrategy`: cách đóng gói và trải phẳng `customValues`.

  Mỗi strategy có `support()` trả về tên object; strategy mặc định dùng key `"default"`.
- **Validate có sẵn:** `SystemHookEvent` validate dữ liệu khi create và update (chạy sớm nhất, `order = Integer.MIN_VALUE`). Lỗi được ném dưới dạng `ObjectValidateException` kèm danh sách `ObjectErrorDetail`.

## Khai báo metadata (`*Meta`)

```java
public final class ImportJobMeta {
    private ImportJobMeta() {}

    public static final String OBJECT_NAME = "importjob";                 // viết thường, liền nhau
    public static final Table<?> TABLE = DSL.table(DSL.name("import_job"));

    public static final TypedAttribute<UUID> ID = attr(TABLE, "id", "id", UUID.class);
    public static final TypedAttribute<String> STATUS = attr(TABLE, "status", "status", String.class);
    public static final TypedAttribute<Map<String, Object>> FILE_INFO =
            attr(TABLE, "fileInfo", "file_info", resolve(Map.class, String.class, Object.class));
}
```

- **Cột trong bảng:** `attr(TABLE, "camelField", "snake_column", ...)`. Cột JSONB dùng `resolve(Map.class, String.class, Object.class)`.
- **DTO hoặc nội dung JSON:** `attr("field", ...)`, không truyền `TABLE`. Ví dụ: `S3FileInfoMeta`, `ImportJobDtoMeta`.
- **Code mới:** không dùng `JSONB.class` hay `new TypedAttribute<>(...)` trực tiếp như code cũ.
- **Meta chỉ là lớp xem dữ liệu có kiểu.** Schema thật lúc chạy là `ObjectMetadata`, lấy từ JPA entity hoặc bảng `object_metadata`. Tên field trong Meta, entity và metadata phải khớp nhau.
- **Tên object** phải giống nhau ở mọi nơi: `OBJECT_NAME`, `resource` của handler, `imports.objects.<key>` và `ObjectServiceFactory`.

## Quy tắc CRUD

- **Repository:**
  - `objectQueryRepository` lọc theo data scope của user.
  - `internalObjectQueryRepository` **không** lọc. Code import/export dùng repository này.
  - Hai bean cùng một class (`InternalObjectQueryRepository` chỉ là lớp con giữ tên cũ), khác nhau ở `ObjectQuery`.
- **Thứ tự xử lý khi ghi:** audit (`AuditStrategy`), rồi đóng gói `customValues`, rồi insert/update. Khi đọc: trải phẳng `customValues` ra map gốc.
- **Update:** gộp dữ liệu mới vào bản ghi hiện có rồi **ghi đè cả dòng**, không phải partial patch. Nếu object có `version`, payload phải gửi kèm `version`.
- **Giá trị mặc định và chuẩn hóa text** (populate) chỉ chạy khi **create**.
- **Cập nhật nhiều dòng bằng biểu thức** (`updateWithExpressions(objectName, List<ExpressionUpdate>)`, cần commons 2.4.2): mỗi phần tử là một dòng theo id, giá trị có thể là biểu thức jOOQ (`quantity + 5`) và điều kiện chặn riêng (`guard`). Tất cả chạy trong một batch JDBC, mỗi dòng `version + 1`. Dòng không được cập nhật (sai id, không qua guard) có count 0, không ném lỗi. Có change log thì chỉ đọc các dòng một lần trước và một lần sau batch; một id cập nhật nhiều lần trong batch chỉ ghi một entry, từ giá trị trước batch tới giá trị cuối.
- **Delete:** xóa mềm nếu metadata có `isDeleted`/`deletedAt`/`deletedBy`/`deletedById`, nếu không thì xóa thật.
- **`code` bị thiếu khi create:** tự sinh ngẫu nhiên. Nếu object có trong `ObjectConstants.PREFIX_OBJECT_CODE` thì sinh theo tiền tố.
- **Nguồn metadata:** `ObjectMetadataFactory` (từ JPA) trước, sau đó đến bảng `object_metadata` (được cache).
- **Filter qua relation** (commons 2.4.5, tên theo reference field từ 2.4.6): `filter` / `lookup` dùng được object khác cùng service qua các `attributeRef` object đã có, không cần cấu hình thêm (`"@<reference>.<field>"` như `"@blanketOrderIdReference.code"` trong `conditions` / `returnFields` / `sorts` / `search`, node `{"relation": …}`, `"*"` trong `returnFields`). Tên ngắn của 2.4.5 (`@blanketOrder`) vẫn chạy. Chi tiết ở mục "Relation (cùng service)" trong README của commons.
  - User không có quyền VIEW object đích: commons ném `RelationAccessDeniedException` (mã `RELATION_ACCESS_DENIED`). Integration log ghi 403.
  - Service cần trả **HTTP 403** cho exception này trong `GlobalExceptionHandler` của mình. Không có handler riêng thì lỗi rơi vào handler `GenericBaseException` và trả 400.
- **Người dùng và tenant:** luôn lấy từ `RequestContextHolder` của commons.

## Change log (audit dữ liệu)

Mỗi dòng ghi qua `objectQueryRepository` / `internalObjectQueryRepository` (route CRUD generic, import, code service gọi repository) của object **được bật** sinh một entry: ai, lúc nào, CREATE / UPDATE / DELETE, giá trị trước / sau của từng field đổi. Entry ghi vào bảng outbox `audit_log_outbox` của service **trong cùng transaction** với dòng dữ liệu, rồi một job đẩy sang `POST /notification/api/v1/internal/audit-logs/batch` (bảng `audit_logs` của notification, category `DATA_CHANGE`).

```yaml
objects:
  api-config:
    service-urls:
      notification: ${NOTIFICATION_SERVICE_URL:}   # host nội bộ, không kèm /notification
  change-log:
    enabled: true
    source-service: ORDER_SERVICE      # AuditServiceEnum của notification
    default-tenant: bhc                # tenant của entry ghi ngoài request (job)
    mask-fields: [password]            # thay giá trị bằng ***
    objects: [v2order, v2orderitem, kpiallocate]   # chỉ object có tên ở đây mới được ghi log
    object-options:                    # tùy chọn riêng, chỉ cho object cần
      v2orderitem:
        parent-field: orderId          # entityParentId
      kpiallocate:
        code-field: code               # entityCode (mặc định "code")
        ignore-fields: [lastSyncAt]
    relay:
      interval: 30s
      batch-size: 200
      max-attempts: 10
      retention: 7d                    # entry đã gửi bị xóa sau thời gian này
```

**Migration** (mỗi service bật change log tự tạo bảng, không có FK):

```sql
create table if not exists audit_log_outbox (
    id               uuid primary key,
    created_at       timestamptz  not null,
    tenant_code      varchar(60),
    source_service   varchar(60),
    tx_id            varchar(60),
    original_tx_id   varchar(60),  -- reserved, not written yet
    action           varchar(60)  not null,
    entity_type      varchar(60)  not null,
    entity_id        varchar(60),
    entity_parent_id varchar(60),
    entity_code      varchar(60),
    actor            jsonb,
    changed_fields   jsonb,
    metadata         jsonb,
    status           varchar(60)  not null default 'PENDING',
    attempts         integer      not null default 0,
    next_attempt_at  timestamptz  not null default now(),
    locked_until     timestamptz,
    sent_at          timestamptz,
    last_error       text
);
create index if not exists idx_audit_log_outbox_status on audit_log_outbox (status, next_attempt_at);
create index if not exists idx_audit_log_outbox_entity on audit_log_outbox (entity_type, entity_id);
```

**Hành vi:**

- **Ghi log hỏng thì việc ghi dữ liệu hỏng theo:** entry được insert trong `beforeCommit` của transaction đang chạy; insert lỗi thì cả transaction rollback, nên không có thay đổi nào được commit mà thiếu log. Không có transaction (route `NOT_SUPPORTED`, job) thì mỗi lệnh ghi tự commit và entry được insert ngay sau nó.
- **Field so sánh** ở dạng field name (đã trải `customValues`): field của metadata trước, sau đó các key khác theo tên. Đổi từ `null` sang có giá trị cũng tính là thay đổi. Số so theo giá trị (`10` = `10.000`). Mặc định bỏ qua `version`, `created_*`, `updated_*`, `deleted_*`, `customValues`. Update không đổi field nào thì không có entry.
- **CREATE** liệt kê mọi field có giá trị. **DELETE** (`metadata.deleteType` SOFT / HARD) liệt kê giá trị của dòng trước khi xóa. Update làm `isDeleted` thành `true` cũng là DELETE SOFT.
- **Method không đọc dòng cũ** (`updateWithCondition`, `updateWithExpressions`, `upsert`, `bulkUpsertReturning`, `delete` / `batchDelete` theo danh sách): khi object được bật, repository đọc các dòng bị ảnh hưởng trước và sau khi ghi bằng `internalQueryService` (không lọc data scope). Upsert theo tên constraint tra cột của constraint trong `pg_constraint` (cache khi tìm thấy; không tìm thấy thì không cache và lần sau tra lại); không tra được cột (sai tên, constraint không thuộc bảng trong `search_path`, cột không có trong metadata) thì upsert bị từ chối bằng `IllegalStateException`, để không ghi nhầm thành CREATE. `insertBatch*` tự gán `id` cho dòng chưa có, vì insert không trả dòng về.
- **Người ghi** lấy từ `RequestContextHolder`; không có user (job, lời gọi nội bộ) thì actor là `SYSTEM`. `txId` là trace id của request, không có thì là id chung của transaction.
- **Relay** chạy trên thread riêng của lib (service không cần `@EnableScheduling`), nhiều instance chạy cùng lúc được (`FOR UPDATE SKIP LOCKED`). Batch bị notification từ chối (4xx) được gửi lại từng entry; entry lỗi thử lại với độ trễ tăng dần (tối đa 1 giờ), quá `max-attempts` thì ở trạng thái `FAILED` để xử lý tay. Giao **ít nhất một lần**: gửi thành công mà chưa kịp đánh dấu SENT thì lần sau gửi lại.
- **Không được ghi log tự động:** dòng ghi bằng JPA, jOOQ / SQL viết tay, `@Modifying` của service (không đi qua repository của lib). Ghi tay bằng `ManualChangeLog` (dưới).

**Ghi tay (`ManualChangeLog`)**: cho lệnh ghi không đi qua repository. Bean có sẵn; truyền dòng trước / sau ở dạng field name, entry đi cùng recorder, outbox, transaction và relay với ghi qua repository (cùng diff, mask, actor, `txId`). Object không bật change log thì không ghi gì, nên lời gọi để nguyên được dù tắt hay bật.

```java
private final ManualChangeLog manualChangeLog;

@Transactional
public void markTaxChecked(UUID accountId, boolean checked) {
    Account account = accountRepository.findById(accountId).orElseThrow();
    Map<String, Object> before = taxState(account);          // chụp trước khi sửa
    account.setTaxChecked(checked);
    accountRepository.save(account);                        // JPA: repository của lib không thấy
    manualChangeLog.update("account", before, taxState(account));
}

/** Chỉ field lệnh ghi đụng tới, kèm id và field mã / cha của object. */
private Map<String, Object> taxState(Account account) {
    return DataRecord.of().with(AccountMeta.ID, account.getId()).with(AccountMeta.CODE, account.getCode())
            .with(AccountMeta.TAX_CHECKED, account.getTaxChecked()).getValues();
}
```

- `create(object, after)`, `update(object, before, after)`, `delete(object, before, soft)`, mỗi method có bản nhận danh sách (dòng khớp nhau theo id).
- Gọi **sau** lệnh ghi, **trong** transaction của lệnh ghi: entry được insert trong `beforeCommit`, nên dòng và log cùng commit / rollback; không có transaction thì entry được insert ngay.
- Dòng chỉ cần các field bị ghi, kèm `id` (thiếu thì `IllegalArgumentException`) và field `code-field` / `parent-field` của object. Field không có ở cả trước lẫn sau không bị coi là đổi; giá trị không đổi thì không có entry. `before` phải là bản chụp, không phải chính map bị sửa sau đó.
- Tên object không có metadata thì báo lỗi `notFound` (như repository).
- Không gọi cho lệnh ghi đã đi qua repository: sẽ có hai entry.
- Entry luôn là `DATA_CHANGE` với CREATE / UPDATE / DELETE; chưa có `subAction` hay `metadata` tùy ý.

## Gọi đối tác bất đồng bộ (integration task)

Từ 2.7.3; chờ kết quả trong request (`inline-wait-ms`) từ 2.7.4. Connector không khai báo `timeout` / `async` chạy **y như 2.7.2 trở về trước**, trừ một điểm (H4): lớp retry ngoài của `IntegrationClientExchange` chỉ còn chạy khi 401 (gia hạn token), nên lỗi IO / timeout gọi tối đa `retry.max-attempts` lần thay vì `max-attempts × max-attempts`.

```yaml
integration:
  async-executor:              # chung cho mọi connector, đây là giá trị mặc định
    max-concurrency: 10        # số luồng gọi đối tác cùng lúc
    queue-capacity: 100        # task chờ luồng; đầy thì task FAILED (EXECUTOR_FULL), không gửi
    sweep-interval-ms: 60000   # chu kỳ job quét task quá hạn
  connectors:
    orderOracleErp:
      timeout:
        connect-ms: 10000      # không khai báo: không giới hạn
        read-ms: 300000        # bắt buộc khi async.enabled (từ 2.7.4), nếu không lời gọi có thể chờ vô hạn
      async:
        enabled: ${ERP_ASYNC_ENABLED:false}
        deadline-ms: 360000    # bắt buộc khi enabled hoặc idempotent; phải > read-ms, sai thì không khởi động
        idempotent: ${ERP_SUBMIT_IDEMPOTENT:false}   # true: cho action retry gửi lại đúng payload cũ
        inline-wait-ms: 25000  # từ 2.7.4; mặc định 0 (trả về ngay như 2.7.3); phải >= 0 và < deadline-ms
```

- **`timeout`:** connector có khối này được `RestClient` riêng (HttpClient của JDK qua `JdkClientHttpRequestFactory`, converter lấy từ `RestClient.Builder` của service), tạo một lần lúc khởi động. Connector khác dùng `integrationRestClient` chung.
- **Handler:** service viết `AsyncIntegrationHandler<I, P>` (bean Spring) cho mỗi loại lời gọi: `prepare` (trong transaction của action, dựng payload + `context`), `call` (chỉ gọi đối tác), `classify` (2xx), `classifyError` (non-2xx có nghĩa nghiệp vụ, ví dụ "already exists"), `onSucceeded` / `onFailed` / `onUnconfirmed`, `onAlreadyDone`, `afterSucceededCommit`, `payloadType`. `action()` mặc định là tên connector; hai handler cùng connector phải đặt `action()` khác nhau.
- **Gọi:** trong `@Handler`, đúng chỗ gọi đối tác: `IntegrationTaskView task = dispatcher.dispatch(actionContext, handler, input);`
  - `async.enabled: false` (mặc định): chạy `prepare` → `call` → phân loại → callback ngay trong request và transaction của action, không ghi bảng, trả `null`. Lỗi là lỗi handler ném trong callback. `afterSucceededCommit` chạy sau commit của request.
  - `async.enabled: true`, `inline-wait-ms: 0` (mặc định): insert task `PROCESSING` trong transaction của action, sau commit mới đưa vào `integrationTaskExecutor` (rollback thì không gửi). Trả `IntegrationTaskView {id, status, deadlineAt}`; service tự thêm vào response (field `integrationTask`). Worker gọi đối tác ngoài transaction, mang security context của người bấm, rồi ghi kết quả và gọi callback trong transaction mới, có fencing theo `attempt_id`. Bản ghi đang có task `PROCESSING`: `integration.task.in_progress`.
  - `async.enabled: true`, `inline-wait-ms > 0` (từ 2.7.4): `prepare` trong transaction của action; insert task `PROCESSING` bằng **transaction riêng** (`REQUIRES_NEW`, commit ngay: request khác thấy khóa, action rollback thì task vẫn còn); gửi đối tác sang `integrationTaskExecutor` **ngay**, request chờ tối đa `inline-wait-ms`. Bên nào nhận kết quả do một CAS quyết định, đúng một lần:
    - **Có kết quả kịp:** callback chạy trong thread và transaction của action như chế độ đồng bộ (`IntegrationTaskContext.isAsync() == false`, có `taskId`; handler được ném lỗi của mình để action thất bại), task được cập nhật (fencing) trong cùng transaction, `afterSucceededCommit` sau commit. `dispatch` trả `null`: service **không** thêm gì vào response. Action rollback (callback ném lỗi hoặc lỗi sau đó): sau rollback task được ghi lại bằng transaction riêng; nếu đối tác đã thành công thì task thành `UNCONFIRMED`, outcome `ACTION_ROLLED_BACK`, giữ `response` của đối tác, log ERROR (đối tác đã làm, bản ghi thì không).
    - **Hết giờ chờ:** ném `IntegrationTaskProcessingException` (`integration.task.processing`, 409, params `object`, `recordId`, `taskId`), transaction của action rollback. Khi đối tác trả lời, worker ghi kết quả và gọi callback trong transaction mới như `inline-wait-ms: 0`. Thread bị interrupt khi đang chờ: giữ cờ interrupt, xử lý như hết giờ.
    - Executor đầy: task `FAILED` (`EXECUTOR_FULL`) bằng transaction riêng, `onFailed` chạy trong request như chế độ đồng bộ.
    - Kết quả không phân loại được (handler `classify` / `onAlreadyDone` lỗi): `UNCONFIRMED` (`UNKNOWN_ERROR`).
  - Task đã kết thúc (`SUCCEEDED` / `FAILED` / `UNCONFIRMED`) không chặn lần `dispatch` sau: bấm lại tạo task mới, `prepare` lại. Chỉ task `PROCESSING` chặn.
- **Phân loại** (`IntegrationOutcomeClassifier`): 2xx theo `classify`; non-2xx hỏi `classifyError` trước. Mặc định: 408, 5xx, read timeout, đứt kết nối, lỗi khác → `UNCONFIRMED`; 4xx khác (cả 401 sau khi đã gia hạn), 429 hết lượt, không kết nối được → `FAILED`. `ALREADY_DONE`: `onAlreadyDone` trả dữ liệu thì `SUCCEEDED`, không thì `UNCONFIRMED`. Ở chế độ async, lời gọi chỉ được gửi lại trong cùng lần (theo `retry`) khi chắc chắn chưa tới đối tác (không kết nối được, 429) hoặc 401.
- **Khóa bản ghi** (`IntegrationTaskGuard`): khi có ít nhất một connector bật async, mọi lệnh ghi qua `ActionExecutor` (update / delete / action / bulk, route portal và internal) lên bản ghi đang có task `PROCESSING` bị từ chối `integration.task.in_progress`; đọc và create không bị kiểm. Chỉ kiểm lần thực thi ngoài cùng của thread (request), không kiểm lệnh lồng trong handler / hook. Object con (dòng hàng) thì service gọi `integrationTaskGuard.check(objectName, id)`. Lệnh ghi qua `ObjectQueryRepository`, JPA, SQL viết tay không bị kiểm. Không bật async thì guard không chạy và không truy vấn gì.
- **Job quét** (`AsyncIntegrationSweeper`, chỉ tạo khi có connector bật async): task `PROCESSING` quá `deadline_at` → `UNCONFIRMED` + `onUnconfirmed`, mỗi task một transaction, nhiều instance chạy cùng lúc được. Chạy không có user: callback nhận request context mà service trả về ngoài request.
- **Action trên `integrationtask`** (quyền `UPDATE` trên object `integrationtask`): `PUT /integrationtask/{id}/retry` gửi lại task `UNCONFIRMED` đúng payload cũ (chỉ khi `idempotent: true`, không thì `integration.task.unconfirmed`); `PUT /integrationtask/{id}/resolve` với `{"status": "SUCCEEDED" | "FAILED", "reason": "...", "data": {...}}` chốt tay task `UNCONFIRMED` (ghi `resolved_by`, `resolve_reason`, change log nếu service bật cho `integrationtask`). Tạo / sửa / xóa `integrationtask` qua route generic bị từ chối.
- **Mã lỗi** (`ObjectErrorCode`): `integration.task.in_progress` (`IntegrationTaskInProgressException`, service trả **409**), `integration.task.processing` (`IntegrationTaskProcessingException`, từ 2.7.4, service trả **409**; message "Tiến trình gửi ERP vẫn đang chạy, vui lòng tải lại sau ít phút", params có `taskId`), `integration.task.unconfirmed`, `integration.task.invalid_status` (`IntegrationTaskStateException`, 400). Service thêm `@ExceptionHandler` cho ba exception này (không có thì ra lỗi chung 500).
- **`integration_log`:** `correlation_id` = id task cho mọi lần gọi của task, `timeout_ms` = `read-ms` của connector.

**Migration** (mỗi service dùng GOM 2.7.3 trở lên tự tạo bảng, không có FK):

```sql
CREATE TABLE IF NOT EXISTS integration_task (
    id              UUID PRIMARY KEY,
    connector       VARCHAR(60)  NOT NULL,
    object_name     VARCHAR(60)  NOT NULL,
    record_id       UUID         NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,   -- PROCESSING / SUCCEEDED / FAILED / UNCONFIRMED
    outcome         VARCHAR(20),
    attempt_id      UUID         NOT NULL,
    attempt_count   INT          NOT NULL DEFAULT 1,
    request         JSONB,
    context         JSONB,
    response        JSONB,
    error_code      VARCHAR(60),
    error_message   VARCHAR(1000),
    resolved_by     VARCHAR(100),
    resolve_reason  VARCHAR(1000),
    started_at      TIMESTAMPTZ  NOT NULL,
    deadline_at     TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    created_by      VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_integration_task_processing
    ON integration_task (object_name, record_id) WHERE status = 'PROCESSING';
CREATE INDEX IF NOT EXISTS idx_integration_task_record
    ON integration_task (object_name, record_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_integration_task_deadline
    ON integration_task (deadline_at) WHERE status = 'PROCESSING';
```

## Import / Export

### Import

Trạng thái: `UPLOADING → VALIDATING → VALIDATED | VALIDATION_FAILED → IMPORTING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED`.

1. Gọi `POST /generic/objects/importjob` với body `{ objectName, service, fileName, originalFileName, mimeType, contentLength }`. API trả về job kèm `uploadUrl`.
2. Client upload file thẳng lên S3 bằng `uploadUrl` (presigned PUT).
3. Gọi `POST /generic/objects/{obj}/imports/{excel|csv}/{importJobId}`. API này **validate rồi import luôn**:
   - Validate: dữ liệu được đọc theo lô `imports.processing.batch-size` (mặc định 100). Dòng hợp lệ vào `import_staging`, dòng lỗi vào `import_error`.
   - Có dòng lỗi thì job chuyển sang `VALIDATION_FAILED`, **không import dòng nào**, và API trả về job đó. Lỗi hệ thống khi validate thì job `VALIDATION_FAILED` và API trả lỗi.
   - Validate không lỗi (`VALIDATED`) thì ghi dữ liệu staging vào bảng thật theo từng lô. API trả về job ở trạng thái `COMPLETED`, `COMPLETED_WITH_ERRORS` hoặc lỗi `FAILED`.
   - Chỉ nhận job `UPLOADING`, hoặc `VALIDATED` (đã validate bằng API cũ: chỉ import). Job ở trạng thái khác bị từ chối.
4. `POST /generic/objects/{obj}/imports/{type}/validate` đã **deprecated**; không cần gọi nữa.
5. Tải file lỗi bằng `GET .../{importJobId}/download-errors`, hoặc file gốc bằng `.../download-file`.

Cấu hình cho từng object, đặt trong `imports.objects.<object>`:

```yaml
imports:
  processing: { batch-size: 100 }
  objects:
    category:
      identity: { fields: [code] }
    product:
      source:
        sheet: Products         # tên sheet Excel; bỏ trống thì đọc sheet đầu tiên
        header: { mode: FIXED, row: 0 }   # AUTO | FIXED; bỏ trống thì dùng imports.header
      depends-on: [category]    # object phải được import trước product
      identity:
        fields: [code]          # khóa nhận diện bản ghi
        strategy: UPSERT        # UPSERT | LOOKUP | NONE
      references:
        categoryId:
          lookups:
            - attributeName: code
              sourceField: categoryCode
          fields: [id, code, name]
```

- **`source.sheet`:** áp dụng cho cả bước đọc file Excel lẫn bước ghi cột lỗi vào file gốc. File **chỉ có 1 sheet** thì luôn đọc sheet đó dù tên khác (file mẫu một sheet cũ vẫn dùng được) và không xử lý sheet phụ thuộc. Tên sheet so sánh **không phân biệt hoa thường** (`Contact` khớp sheet `contact`). File nhiều sheet mà không có sheet trùng tên thì ném `ImportConfigurationException`.
- **`imports.objects.<obj>.header`** (cách khai báo cũ) vẫn dùng được, nhưng đã deprecated; hãy chuyển sang `source.header`.
- **Dòng tiêu đề hợp lệ** khi có ít nhất một cột trùng field của object và số cột được nhận (kể cả cột mà handler `@ImportDetectHeaderHandler` của service map sang tên không phải field, ví dụ mỗi KPI một cột) chiếm ít nhất một nửa số cột của dòng.
- **`depends-on`:** `ImportConfigService.resolveOrder()` trả về thứ tự import của mọi object đã cấu hình, object phụ thuộc đứng sau các object nó phụ thuộc vào; các object độc lập xếp theo tên. Cấu hình được kiểm tra **khi khởi động**: object trong `depends-on` chưa được khai báo, hoặc phụ thuộc vòng (`a -> b -> a`), sẽ ném `ImportConfigurationException` và ứng dụng không chạy lên. Với Excel, API import dùng `depends-on` để import nhiều sheet trong một file (xem bên dưới).
- **Override từng bước:** mỗi bước (đọc file, map dòng, map ô, resolve reference, insert theo lô) là một named action trong `ObjectActionNamed`, nên có thể override bằng handler.
- **Transaction:** mỗi lô và mỗi lần đổi trạng thái chạy trong `REQUIRES_NEW`. Lô lỗi được chạy lại từng dòng một.
- **Import lỗi:** job chuyển sang `FAILED`, kèm `errorMessage` và `completedAt`.

### Import nhiều sheet trong một file Excel

Ví dụ một file có sheet `Order` và `OrderItem`:

```yaml
imports:
  objects:
    v2order:
      source: { sheet: Order }
    v2orderitem:
      source: { sheet: OrderItem }
      depends-on: [v2order]
```

Tạo job với `objectName` là object gốc (`v2order`), upload file và gọi API import như bình thường: `POST /generic/objects/v2order/imports/excel/{importJobId}`.

Cách chạy:

- Sheet của `v2order` được validate và import như một object đơn. Nếu nó được import (`COMPLETED` hoặc `COMPLETED_WITH_ERRORS`), thư viện xử lý tiếp các object có `depends-on` tới `v2order` (trực tiếp hoặc gián tiếp), theo thứ tự phụ thuộc. Object phụ thuộc **bắt buộc** có `source.sheet`.
- Mỗi object được validate **và import xong** rồi mới tới object sau, vì sheet con tra mã của dòng cha trong database.
- **Cả file là một job** (job của `v2order`). Mỗi sheet là một lượt chạy (`ImportJobService.startSheetRun`) có `runId` riêng: dòng staging và lỗi của sheet mang `import_id = runId` (sheet gốc dùng chính `jobId`), còn bộ đếm (`totalRows`, `validRows`, `successRows`, `errorRows`) và trạng thái cộng dồn vào job. Handler của service chạy với `resource` là object của sheet và nhận `runId` làm `jobId`; cần job thì gọi `ImportJobService.jobIdOf(runId)`.
- Job lưu tóm tắt từng sheet ở `fileInfo.sheets: [{ objectName, sheet, runId, status, totalRows, validRows, successRows, errorRows, errorMessage }]` (số dòng là phần của riêng sheet đó).
- **Lỗi khi validate thì dừng, lỗi khi import thì chạy tiếp:** sheet `VALIDATION_FAILED` (hoặc `FAILED`, lỗi hệ thống cả bước import) dừng cả lượt, các sheet sau bị `SKIPPED`; sheet `COMPLETED_WITH_ERRORS` (một số dòng lỗi lúc ghi) không dừng. Sheet của object phụ thuộc không có trong file cũng bị `SKIPPED`. Mỗi lượt so số dòng lỗi của job với lúc lượt bắt đầu (`ImportJobService.errorRowsBefore`), nên lỗi của sheet trước không làm sheet sau thành `VALIDATION_FAILED`.
- Sheet gốc đã được import mà có sheet nào lỗi: job `COMPLETED_WITH_ERRORS`, `errorMessage` liệt kê `"<sheet>: <lỗi>"`. Các sheet trước sheet lỗi **đã được ghi** (mỗi sheet commit riêng).
- Tải file lỗi theo job: lỗi của từng sheet ghi vào cột `Result` của **đúng sheet đó** trong một lần ghi file. Lấy lỗi một sheet trên màn hình: lọc `importerror` theo `importId = runId`.
- Response là job của `v2order`, thêm `sheets` (như `fileInfo.sheets`). `sheetJobs` (chỉ các sheet phụ thuộc, `importJobId` = job của file) còn giữ cho client cũ. Lỗi của sheet phụ thuộc nằm trong job, không làm API trả lỗi.
- API chạy đồng bộ trong request và không có `@PreAuthorize`, giống trước đây.
- Có thể override toàn bộ luồng bằng handler cho action `ObjectActionNamed.Excel.IMPORT_FILE` / `Csv.IMPORT_FILE` (mặc định: `ImportFileService`).

### Import detail cho một header (`import_details`)

Người dùng đang ở một header (ví dụ một đơn hàng) và import các dòng detail của **đúng header đó**; file không cần cột khóa cha.

Cấu hình trên object detail:

```yaml
imports:
  objects:
    v2orderitem:
      source: { sheet: OrderItem }
      depends-on: [v2order]                  # import header + detail trong một file (mục trên), không bắt buộc
      parent:
        object: v2order                      # object header
        field: orderId                       # khóa cha trên detail; thư viện tự điền
        reference-fields: [id, code, name]   # snapshot <field>Reference (field phải có attributeRef), lấy từ header
        required: true                       # detail chỉ import được qua header
        identity: [productId, unitId]        # identity trong phạm vi một header (field khóa cha tự đứng đầu)
        modes: [APPEND, UPSERT]              # APPEND = chỉ thêm; UPSERT = thêm hoặc sửa trong phạm vi header
        default-mode: APPEND
        max-rows: 2000                       # 0 = không giới hạn
```

Gọi:

1. `POST /generic/objects/importjob` với `objectName` = object detail (`v2orderitem`), upload file Excel lên `uploadUrl` (như mọi import).
2. `PUT /generic/objects/v2order/{orderId}/import_details` với `{ "importJobId": "…", "version": <version header đang hiển thị>, "mode": "APPEND" }`. Route kiểm quyền action `import_details` trên object header (`canProcessAction`): user cần quyền `UPDATE` trên object header.
3. Response `{ "job": …, "parent": … }` (header đọc lại sau import); có lỗi thì tải file lỗi theo `jobId` như thường.

Thư viện (`ImportDetailsService`):

- Đọc header bằng repository có data scope của user (không thấy → 404), so `version` (lệch → 409); kiểm job (`UPLOADING`, object có `parent.object` = header, chưa gắn header khác, file Excel) và `mode` → 400.
- Gọi `import_parent_check` (`resource` = object detail) để service từ chối nếu header không còn sửa được, rồi lưu header vào `fileInfo.context` của job (`ImportContext`).
- Validate: mọi dòng được điền `field` = id header và `<field>Reference` = snapshot; file có cột khóa cha khác header → lỗi dòng `PARENT_MISMATCH`; identity trong phạm vi header, trùng trong file → `DUPLICATE_IN_FILE`; `APPEND` và dòng trùng detail sẵn có của header → `DUPLICATE_IN_PARENT`; quá `max-rows` → job `VALIDATION_FAILED`. Có lỗi thì không ghi gì.
- Import như thường (mỗi lô một transaction, lô lỗi thử lại từng dòng; hook `bulk_*` của service vẫn chạy). `UPSERT` sửa đúng detail của header.
- Route action chạy trong transaction (`GenericObjectService.objectAction`); `import_details` tạm dừng transaction đó (`NOT_SUPPORTED`) như đường `POST /{object}/imports/...`, vì job được cập nhật trong các transaction riêng khi ghi lô. `import_parent_completed` chạy trong một transaction riêng (`REQUIRES_NEW`).
- Có dòng được ghi → gọi `import_parent_completed` **một lần** (`resource` = object detail) để service tính lại header; handler lỗi → job `COMPLETED_WITH_ERRORS`.
- `required: true`: `POST /generic/objects/{detail}/imports/{type}/{jobId}` không qua header bị từ chối (400). Sheet detail của file header + detail vẫn chạy (là lượt của job header).

Điểm mở rộng của service (theo kiểu `@ImportRowMappingHandler`):

```java
@ImportParentCheckHandler(resource = "v2orderitem")
public void checkOrder(ActionContext<ImportParentContext> context) { /* throw để từ chối */ }

@ImportParentCompletedHandler(resource = "v2orderitem")
public void recomputeOrder(ActionContext<ImportParentResult> context) { /* tính lại tổng, lịch sử… */ }
```

Trong các handler / hook khác, `ImportJobService.contextOf(importId)` trả header của job (null khi import thường).

### Export

Trạng thái: `PENDING_EXPORT → EXPORTING → COMPLETED | FAILED`.

- Gọi `POST /generic/objects/exportjob` với body `{ objectName, filterCriteria }`. File được tạo từ template Excel trên S3 (`exports.template` hoặc `exports.objects.<obj>.template`), rồi stream lên S3. Export chạy bất đồng bộ trên executor `dataExchangeTaskExecutor`, nên service cần bật `@EnableAsync`.
- `GET /generic/objects/exportjob/{id}` trả về `downloadUrl` (presigned).

**Đường dẫn file trên S3:** `{tenantCode}/{aws.s3.sub-folder}/{objectName}/{yyyyMMdd}/{uuid}_{epochMillis}.{ext}`.

## Schema của object

`GET /api/v1/internal/generic/objects/{obj}/schema` mô tả object cho service khác đọc (ví dụ engine luật cần biết field nào có, kiểu gì, trỏ tới object nào). Không có bảng mới: schema dựng từ metadata mà service đăng ký từ JPA entity lúc khởi động (`ObjectMetadataFactory`), entity vẫn là nguồn sự thật.

```json
{
  "object": "accountsite",
  "idField": "id",
  "hash": "sha256:…",
  "attributes": [
    {"name": "code", "type": "STRING", "javaType": "String", "required": true, "multiSelect": false},
    {"name": "provinceId", "type": "UUID", "javaType": "UUID", "required": false, "multiSelect": false,
     "ref": {"object": "geography", "snapshotField": "provinceIdReference"}},
    {"name": "status", "type": "STRING", "javaType": "String", "required": false, "multiSelect": false,
     "enumValues": ["ACTIVE", "INACTIVE"]}
  ]
}
```

- **`type`:** `STRING`, `NUMBER`, `BOOLEAN`, `DATE`, `DATETIME`, `UUID`, `STRING_LIST`, `UUID_LIST`, `JSON`, `OTHER`. `javaType` giữ kiểu Java khai báo.
- **`enumValues`:** khi field có `@AttributeDefinition(enumType = …)`, đúng tên hằng (phân biệt hoa thường).
- **`ref`:** khi `@AttributeDefinitionRef` có `objectRef`.
  - `snapshotField` là key JSON `<field>Reference`.
  - `snapshotFields` chỉ có khi metadata khai báo (`AttributeRef.attributeRefs`). commons 2.4.2 chưa điền giá trị này từ annotation, nên hiện nay field này vắng.
- **Không có trong schema:**
  - tên bảng, tên cột, jOOQ, phạm vi bảo mật;
  - field audit / soft-delete / workflow của `AuditingEntity`, `BusinessProcessEntity`, `SoftDeleteEntity`;
  - `version` và `customValues`.
- **`hash`:** SHA-256 của nội dung schema, giống nhau trên mọi instance. Đổi khi đổi field, kiểu, enum hay ref; dùng để phát hiện schema đã đổi.
- **`GET …/schema`:** liệt kê `{object, hash}` của mọi object của service (bỏ entity của chính lib như import / export job).
- **Quyền:** như các API nội bộ khác, endpoint không kiểm quyền. Service nào để `/internal/**` `permitAll` thì nên chặn ở mạng hoặc yêu cầu JWT nội bộ.

## Gọi service khác

- **`GenericObjectInternalService`:** gọi API nội bộ của service khác tại `objects.api-config.service-urls.<service> + "/" + <service>`; service không khai báo (hoặc giá trị trống) thì dùng `objects.api-config.base-url + "/" + <service>`.
  - Key của `service-urls` là tên service trong `ObjectServiceFactory`: `user`, `customer`, `order`, `inventory`, `master-data`, `catalog`.
  - Đường dẫn `/internal` thường bị gateway công khai chặn: trỏ `service-urls` vào host nội bộ của từng service.
  - Service đích được tra trong `ObjectServiceFactory`. **Object mới gọi chéo service phải được thêm vào map này** với key viết thường; nếu không, lời gọi ném `IllegalStateException` có tên object.
- **`ObjectClientService`:** class trừu tượng. Kế thừa class này và implement `getBaseUrl()` để có sẵn các hàm filter / getById / query / conversion / `getSchema` / `getSchemas` tới `/api/v1/internal`. `GenericObjectInternalService.getSchema(object)` đọc schema từ service sở hữu object (định tuyến như các lời gọi khác).
- **Tích hợp hệ thống ngoài:** dùng `integrationClient.connector(<name>)` với cấu hình `integration.connectors.<name>`.
  - OAuth token được cache trong Redis.
  - Request được ghi vào bảng `integration_log`.
  - Muốn ghi log cho một endpoint thì gắn `@IntegrationLogging(connector, operation)`.

## Lỗi

- **Tạo lỗi:** dùng factory `ObjectErrors` (`notFound`, `validateException`, `payloadInvalidException`…).
- **Mã lỗi:** khai báo trong `ObjectErrorCode`.
- **409:** `IntegrationTaskInProgressException` (`integration.task.in_progress`) và `IntegrationTaskProcessingException` (`integration.task.processing`), như 403 của `RelationAccessDeniedException`.
- **Chi tiết lỗi validate:** `ObjectErrorDetail { code, params, message }`. Message validate lấy từ commons và là tiếng Việt.

## Build & phát hành

```bash
./gradlew compileJava
./gradlew clean publishToMavenLocal -x test   # giống lệnh JitPack chạy
```

1. Muốn nâng commons thì sửa `msmCommonVersion` trong `build.gradle.kts`. Commons phải có tag và build thành công trên JitPack trước.
2. Merge thay đổi vào `main`.
3. Tạo GitHub Release với tag mới (semver, không có tiền tố `v`).
4. Mở <https://jitpack.io/#lib4jx/generic-object-management> và bấm **Get it** ở tag mới để build.

`jitpack.yml` cố định JDK `21.0.2-open` qua SDKMAN.

## Hạn chế đã biết

- `MasterDataApiService` và chế độ chạy localhost có URL viết cứng trong code.
- `ExportJobMeta.TABLE` dùng schema `"bhc"`, trong khi các Meta khác không có schema.
- Test tự động mới chỉ có cho phần cấu hình import, `ImportFileService` và `FileReaderService.getSheetNames` (`./gradlew test`).
