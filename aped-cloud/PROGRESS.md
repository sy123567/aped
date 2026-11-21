# aped-cloud 迁移进度（分批验收记录）

## 批次 0：骨架 + 依赖拉通 —— ✅ 完成（2026-11-19）

验收项（计划第六节批次 0 标准）：

- [x] **依赖通道打通**：重建 Maven 本地中转 `tools/maven-relay.py`（127.0.0.1:18099，curl 拉取 + `~/workspace/m2cache` 缓存，复用既有 `~/.m2/settings.xml` 的 localcache 镜像配置）。实测经中转拉取 `spring-boot-starter-parent-3.5.7.pom` 返回 200。
- [x] **版本核定**：Spring Boot 3.5.7 + Spring Cloud 2025.0.0 + JDK 21，所需构件（spring-cloud-dependencies 2025.0.0、gateway-server-webflux、eureka-server/client、openfeign、loadbalancer）在 Central 均确认存在。
- [x] **14 模块骨架**：父 pom 多模块（packaging pom，统一 dependencyManagement 导入 spring-cloud-dependencies）；12 个业务服务各有独立 pom、启动类（`@SpringBootApplication + @EnableDiscoveryClient + @EnableFeignClients`）、`application.yml`（服务名/端口 8100–8111/Eureka 注册/Actuator）、探活接口 `GET /ping`（返回原项目统一格式 `{ok:true,data:{service,desc,ts}}`）；eureka-server（8761）、gateway-service（8080，12 条 `lb://` 路由 `/gw/<服务>/**` + StripPrefix=2）。
- [x] **全量编译**：`mvn -B compile` 14 模块全部通过（EXIT=0，约 48 秒，依赖经中转下载）。
- [x] **实跑验收**（抽样 4 个 JVM，其余 10 个服务同骨架同配置、已过编译，未逐个启动——本机内存 6GB 不宜 14 个 JVM 同跑）：
  - Eureka：`GET /actuator/health` → `{"status":"UP"}`；`/eureka/apps` 显示 GATEWAY-SERVICE、ORDER-SERVICE、RATE-SERVICE 均为 UP。
  - rate-service 直连：`GET http://127.0.0.1:8103/ping` → `{"ok":true,"data":{"service":"rate-service","desc":"运价卡、计费规则、运费试算",...}}`；order-service（8101）同。
  - 网关转发：`GET http://localhost:8080/gw/rate-service/ping` 与 `/gw/order-service/ping` 均经 `lb://` 路由正确返回对应服务 JSON；网关自身 health UP。
  - 验收后已停掉全部测试 JVM，端口释放；Maven 中转保持运行供后续批次构建。

批次 0 踩坑与修复（已同步进 README“本机坑”）：
1. Java 客户端连 127.0.0.1 的 Eureka 卡死（沙箱 IPv4 回环丢数据）→ 全部 JVM 加 `-Djava.net.preferIPv6Addresses=true`，Eureka 地址改 localhost、实例 hostname=localhost 后注册与调用全通。
2. Gateway 首版 `application.yml` 路由列表缩进错误导致启动即 YAML 解析失败 → 已修正并重新打包验证。

## 批次 1：auth + customer + gateway 鉴权 —— ✅ 完成（2026-11-19）

实现内容：

- **auth-service**（`AuthController` / `UserStore` / `JwtCodec`）：`POST /auth/login`、`GET /auth/me`、`POST /auth/logout`、`POST /auth/password`，路径与返回格式与原版 `services/auth.js` 一致（`{ok:true,data:{token,expires_at,user}}`，`expires_at` 为 `yyyy-MM-dd HH:mm:ss` 本地时间，TTL 12 小时同原版）。账号种子照抄原版 `db.js`：admin / dispatch(dispatcher) / courier / claims / finance / ops + 客户 zhangsan/lisi/wangwu（customer_id 1/2/3），初始密码统一 123456；ROLE_MODULES、ROLE_LABEL 逐字照抄。token 机制由原版“随机串 + 服务端会话表”换成 **JWT（HS256，JDK 手写编解码，未引第三方库）**；退出用内存黑名单等价原版删会话；改密用载荷里的口令版本号 `pwdv` 使旧 token 全部失效（等价原版“改密删全部会话”，且避免了秒级 iat 时间戳的同秒误杀）。口令存储为内存 PBKDF2 加盐哈希（原版 SQLite + scrypt，落库留待后续批次）。
- **customer-service**（`CustomerController`）：客户档案子集，对应原版 `services/order.js` 的 `GET /customers`、`GET /customers/:id`，种子为张三/李四/王五三条档案。身份由网关注入的 `X-User-*` 头传入，本服务只做数据范围控制，无身份头一律 401。**范围裁剪**：原版 `/customers` 附带的 order_cnt/total_fee/bill_cnt/unpaid 汇总与 `/customers/:id` 附带的 orders/waybills/bills 子列表依赖 order/billing 批次，本批次只返回档案本体（`/customers/:id` 返回 `{customer}`），留待对应批次补齐。
- **gateway-service**（`JwtAuthGlobalFilter`，GlobalFilter，order=-100）：规则逐条照抄原版 `server.js`——公开路径仅 `/ping`、`/auth/login`；其余路径无 token/签名错/过期一律 401 `{ok:false,error:"未登录或登录已过期"}`；前缀角色表与原版 `gate()` 完全一致：计费 `[/rates,/pricing,/billing]` → finance/customer，揽派 `[/dispatch,/employees,/flights,/pickup,/transit,/delivery]` → dispatcher/courier，理赔 `[/exceptions,/claims]` → claims，admin 恒通过，越权 403 文案 `当前角色（X）无此操作权限`；允许查询串 `?token=`（同原版）。校验通过后注入 `X-User-Id/Name/Role/Customer-Id/Employee-Id` 头，并先剥除客户端自带的同名头防伪造。入口路径沿用批次 0 约定 `/gw/<服务>/**`（StripPrefix=2），服务内路径与原版 `/api` 后路径逐字一致。

实跑验收（eureka 8761 / gateway 8080 / auth 8100 / customer 8102 四个 JVM 实跑，Eureka 显示三服务均 UP；`mvn -B package` 四模块成功、全量 `mvn -B compile` 14 模块 BUILD SUCCESS；验后已停全部测试 JVM、端口释放）：

- [x] 经网关 `POST /gw/auth-service/auth/login`（admin/123456）→ **200**，返回三段 JWT + `expires_at` + user（含 role_label、modules）；zhangsan 登录返回 customer_id=1、modules=[my,orders,tracking]。
- [x] 密码错误 → **401** `账号或密码错误`；缺密码 → **400** `请输入账号和密码`（与原版一致）。
- [x] 无 token、垃圾 token、手工签发的过期 token 经网关访问 `/gw/customer-service/customers` → 均 **401**。
- [x] 角色越权（网关层）：courier→计费前缀 **403**、finance→理赔前缀 **403**、claims→揽派前缀 **403**，文案与原版一致；允许角色（finance→计费、dispatch→揽派）越过鉴权层（下游服务未启动返回 503，证明不是被网关拦下）。
- [x] 客户数据隔离：zhangsan 经网关 `GET /customers` 只返回自己 1 条；`GET /customers/1` → **200**；`GET /customers/2` → **403** `只能查看自己的账户`（原版同文案同状态码）；伪造 `X-User-Role: admin` 头也无效（网关剥除），仍 403。
- [x] 直连 customer-service 无身份头 → **401**；admin token 经网关看客户列表为全量 3 条；courier（非门控路径）可达客户列表，与原版 orderService 不设前缀门一致。
- [x] `GET /gw/auth-service/ping` 免登录 200；`/auth/me` 带 token 200、退出登录后直连 `/auth/me` → **401**（黑名单生效）；改密全流程实测：改密 200 → 旧 token 401 → 新密码登录 200 → 新 token 200 → 改回 123456 成功。

遗留/差异（如实记录）：

1. **JWT 与原版 token 的契约差异**：响应字段与形态不变（前端契约不变），但 token 从不透明随机串变为自包含 JWT；原版会话在服务端可即时全量吊销，JWT 侧退出吊销靠 auth-service 内存黑名单——**网关不查黑名单**，已退出的 token 在过期前仍能通过网关到达非 auth 服务（靠下游按身份头做范围控制兜底）。生产化时需引入共享吊销存储（如 Redis）或缩短 TTL，留待后续批次。
2. auth/customer 目前为**内存数据**（种子与原版一致），服务重启即重置，密码修改不持久；数据库落库未在本批次范围内。
3. customer 的订单/账单汇总字段与子列表未返回（见上“范围裁剪”），待 order/billing 批次补齐。

## 批次 2：rate + order —— ✅ 完成（2026-11-19）

实现内容：

- **rate-service**（`PricingEngine` / `RateController`）：原版 `core/pricing.js` 计费引擎逐项翻译，口径一字不改——（首重+阶梯续重[10/30/100kg 三段累进系数 1.0/0.9/0.75/0.6]）×时效系数×区域系数（同城 0.9/省内 1.0/同大区 1.15/跨大区 1.35/偏远 1.8，省份识别与原版同序）+ 燃油 8% + 保价 3‰ + 包装 2/超重 15（>30kg）/超长 20（>100cm），月结折扣 0.95 只打运费+燃油，最低一票 12 元兜底；体积重 = 长×宽×高÷6000（或 volumeL×1000÷6000）与实重取大后进位整公斤。数值语义对齐 JS：double 中间量 + 对精确二进制值四舍五入（等价 `+x.toFixed(2)`），JSON 整数值不带小数点。运价卡（次日达 1.2/隔日达 1.0/经济件 0.85 等）与 pricing_rules 均为原版 `db.js` 种子的内存副本。对外接口：`GET /rates`、`GET /pricing/rules`（与原版 billing.js 同路径同形态）、`POST /pricing/quote`（试算本体，供 Feign 调用，返回 = computeFee 结果 + 内部字段 promise_hours）。
- **order-service**（`OrderController` / `RateClient` / `OrderData`）：`POST /orders/quote` 本地不算钱，组装参数经 **Feign 按服务名调 rate-service**，返回 computeFee 结果 + promise_time（剔除内部 promise_hours）；`POST /orders` 校验链与原版逐项一致（必填/重量范围/时段/checkArea 服务范围——含那曲安多县受限口径与原版“按表序首个城市命中”的判定），客户身份锁定、订单号 `SO+yymmdd+5 位流水`（nextNo 同规则）、状态 待派单、预估费来自 rate；`GET /orders` 客户只看自己、附 customer_name/monthly。角色限制在服务内按网关注入的 `X-User-*` 头执行（对应原版 `allow('dispatcher','customer')`，admin 恒通过）。订单存内存（落库留待后续批次）；客户月结标记用与原版同种子的本地副本（张三 0/李四 1/王五 1）。
- **Feign 降级**：order-service 引入 `spring-cloud-starter-circuitbreaker-resilience4j`，`@FeignClient(fallback=…)` + `spring.cloud.openfeign.circuitbreaker.enabled=true`，Feign 超时 2s/3s。降级口径：rate 不可用时**不本地估算、不臆造金额**，试算/下单统一返回 503 `{ok:false,error:"计费服务暂不可用，暂时无法试算（降级）",degraded:true}`，下单不落单。

实跑验收（eureka 8761 / gateway 8080 / auth 8100 / rate 8103 / order 8101 五个 JVM 实跑，Eureka 显示四服务均 UP；`mvn -B package` 全 14 模块成功、全量 `mvn -B compile` BUILD SUCCESS；Node 原版在 /tmp 副本上临时启动于 3001 做对照——原目录文件零改动；验后已停全部测试 JVM 与临时 Node，端口释放）：

- [x] **金额对照（同输入双跑，解析后 JSON 逐项深比对，含 total、items 每项 amount/label/rule、detail 文本、zone/zone_coef/chargeKg/volWeight）**：`GET /rates`、`GET /pricing/rules` 两边完全一致；8 组试算全部一致（promise_time 为时间相关字段，格式一致、不参与比对）：
  | # | 场景 | Node 总价 | Java 总价 |
  |---|---|---|---|
  | 1 | 次日达 2.5kg 同城（杭州滨江） | 23.33 | 23.33 |
  | 2 | 经济件 体积重 20kg>实重 1kg 跨大区（深圳） | 90.47 | 90.47 |
  | 3 | 隔日达 5kg 保价 2000 跨大区（北京） | 46.82 | 46.82 |
  | 4 | 次日达 35kg 超重+120cm 超长 偏远（新疆） | 349.93 | 349.93 |
  | 5 | 隔日达 3kg 月结客户李四 同大区（上海） | 23.60 | 23.60 |
  | 6 | 经济件 0.5kg 同城 最低价兜底 | 12.00 | 12.00 |
  | 7 | 隔日达 45kg 阶梯跨三段+超重 同大区（南京） | 219.93 | 219.93 |
  | 8 | 次日达 volumeL 兼容入参+包装 省内（宁波） | 38.29 | 38.29 |
- [x] **下单对照**：同一 body 双跑 `POST /orders`，est_fee/est_detail/zone/zone_label/items/area_note 全部一致（23.60，月结折扣 -1.24），order_no 同为 `SO26111900001`；Java 列表 `GET /orders` 返回该单（customer_name=李四、monthly=1、status=待派单）。id 不同（Node 副本库已有 2 单 → id=3，Java 内存从 1 起）属数据状态差异，非契约差异。
- [x] **校验/权限对照**：quote 无重量 → 两边同为 400 `请填写预估重量`；下单缺时段 → 400 `请选择上门取件时段`；收件地址为那曲安多县 → 400 `那曲市安多县 暂不支持上门揽收，建议就近网点自寄`；courier 经网关下单 → 403 `当前角色（courier）无此操作权限`（原版 allow 文案）。
- [x] **Feign 链路与降级实测**：停掉 rate-service 后，经网关（admin token）试算 → **503** `{ok:false,error:"计费服务暂不可用，暂时无法试算（降级）",degraded:true}`；下单同样 503 且未落单（列表仍 1 条）——证明 order 本地不算钱、降级真实生效。

遗留/差异（如实记录）：

1. **内部契约新增**：`POST /pricing/quote` 是迁移新增的服务间接口（原版 computeFee 为进程内函数调用，无 HTTP 路径）；其返回比 computeFee 多内部字段 promise_hours，order 对外响应已剔除，前端契约不变。
2. **运价/规则写接口未迁**：原版 `PUT /rates/:code`、`PUT /pricing/rules/:key`（finance）属 billing.js，本批次未迁（留待批次 3 billing 一并处理）；当前 rate 用定版种子值，与原版“全新种子库”状态一致——若原版库的规则曾被 PUT 改过，两边会不一致，对照时以种子值为准。
3. **order 数据为内存实现**：订单不落库、重启即重置，订单号流水只数内存中的当日订单；events 轨迹未写（轨迹查询属批次 6 track-service）。`POST /orders/:id/cancel` 与 `GET /waybills` 本批次未迁。
4. **order→customer 暂未走 Feign**：下单查客户月结标记用本地同种子副本（customer-service 批次 1 只提供档案查询、无服务间月结查询接口），待客户数据落库批次再接通计划中的 Feign 调用。

## 批次 3：billing —— ✅ 完成（2026-11-19）

实现内容：

- **billing-service**（`BillingController` / `WaybillController` / `BillingStore` / `CustomerClient` / `RateClient` / `FeignHeaderConfig`）：
  - 账单全流程逐项照原版 `services/billing.js`：`GET /billing`（客户只看自己，附 customer_name/monthly）、`POST /billing/generate`（出账）、`GET /billing/:id`（bill + 运单级明细 + 核销流水 + frozen_amount）、`POST /billing/items/:id/freeze`（冻结/解冻并按未冻结明细重算账单金额；已结清账单拒绝再动）、`POST /billing/settle`（写 receipts 流水 + 累计 paid_amount，付满转「已结清」否则「部分核销」，兼容旧前端字段名 `paid`）、`GET /billing/:id/receipts`。写操作角色限制对应原版 `allow('finance')`（admin 恒通过），按网关注入的 `X-User-*` 头在服务内执行。
  - **出账幂等照原版语义**：同客户同账期已有账单 → 400 `该账期已生成账单 X，请勿重复出账`（与账单状态无关）；只汇总该账期内 `status='已签收'` 的运单计费快照；原版单体单事务（tx）在本服务内以单锁串行多行写等价实现，跨服务无分布式事务（出账只读 customer 校验 + 本地写，无跨服务写链）。
  - **运单计费快照**（waybills 表按计划归 billing）：`POST /internal/waybills` 是原版 `createWaybillForOrder` 的服务间版本——经 **Feign 调 rate-service** 试算并取运价卡（本地不算钱，rate 不可用返回 503 degraded 且不落单），费率行（首重/续重/体积除数）与 fee_snapshot 一并快照进运单行；`POST /internal/waybills/:no/sign` 写「已签收」+ signed_at（原版由派送签收环节写，批次 4 接入后由 task-service 驱动）；`GET /waybills` 运单计费查询（对应原版 order.js 的 `GET /waybills`，客户只看自己）。
  - **客户信息走 Feign → customer-service**（计划调用矩阵 billing → customer）：出账时校验客户存在与月结标记、列表/详情的 customer_name/monthly 均经此取，身份头由 `FeignHeaderConfig` 从当前请求透传；customer-service 不可用时降级回落本地同种子副本（与批次 2 order-service 口径一致）。
- **rate-service 补迁批次 2 遗留的两个写接口**：`PUT /rates/:code`、`PUT /pricing/rules/:key`（原版挂在 billing.js，但 products / pricing_rules 两表按计划拆分表归 rate-service，故落在本服务并在此注明）；`PricingEngine` 的运价卡与规则改为可变状态，改价即时影响后续试算；响应形态（整行产品 / `{key, value, note}`）与原版一致。

实跑验收（eureka 8761 / gateway 8080 / auth 8100 / customer 8102 / rate 8103 / order 8101 / billing 8104 七个 JVM 实跑，Eureka 显示六个业务服务均 UP；Node 原版在 /tmp 副本上以全新种子库启动于 3001 做同流程对照，原目录零改动；`mvn -B package` 全 14 模块成功、全量 `mvn -B compile` BUILD SUCCESS；验后已停全部测试 JVM 与临时 Node，端口释放）：

- [x] **Node 侧基线全流程脚本 38 项全过**：下单 → 自动派单 → 接单 → 揽收生成运单（费 **28.6**，明细：基础运费 23 + 燃油 1.84 + 保价 3 + 包装 2 + 月结折扣 −1.24）→ 中转 → 派件 → 签收 → 出账（金额 28.6、1 条）→ 重复出账被拒 → 详情 → 冻结（金额→0、frozen_amount=28.6）→ 解冻恢复 → 核销 10 元（部分核销）→ 余款核销（已结清）→ 流水 2 条 → 各错误路径与隔离 → PUT 改价后试算 26.44。
- [x] **Java 侧经网关同流程脚本 42 项全过，且与 Node 逐项对照一致**：下单预估费 23.6、运单费 28.6、计费明细文本逐字一致、运单号 `8826111900001` 与账单号 `BILL2611190001` 两边同号（同日同流水规则）、出账金额 28.6 / 明细 1 条、冻结/解冻后金额、核销三态（部分核销 paid 10 / remaining 18.6 → 已结清 remaining 0）、流水金额与凭证/经办人、详情 bill 字段形态均一致。
- [x] **幂等实测**：出账成功后立即重复 generate → 400 且文案带原账单号；账单结清后再次 generate 仍被拒（dup 检查与状态无关，同原版）；重复核销已结清账单 → 400 `该账单已结清`；结清后冻结明细 → 400 `账单已结清，不能再冻结/解冻明细`。
- [x] **错误与权限对照（两边一致）**：非月结客户出账 → `该客户未开通月结协议`；客户不存在 → 404 `客户不存在`；无已签收运单 → `该账期内没有已签收的计费运单`；缺参 → `customer_id 与 period 必填`；客户看他人账单详情 → 403 `只能查看自己的账单`，客户列表只见自己 1 条；客户调 generate/PUT → 穿过网关后被服务内 allow 403；快递员调 generate/PUT/账单列表 → 网关计费前缀 gate 403（文案同原版）。
- [x] **PUT 费率/规则实测**：finance 改 `P_GD.cont_price` 4→5、`fuel_rate` 0.08→0.1 后，同一试算输入两边同为 **26.44**（25.3 + 2.53 − 1.39）；`GET /rates` 可见新值；产品不存在 404 `产品不存在`、规则不存在 404 `规则不存在`、value 非数字 400 `value 必须是数字` 两边一致。
- [x] **验收中修复 1 个真 bug**：`PUT /pricing/rules/:key` 传非数字 value 时，RateController 三元表达式自动拆箱 NPE 返回 500；改为显式分支后重测返回 400 `value 必须是数字`，并顺带确认 rate 重启后运价/规则恢复种子值（内存口径）。

遗留/差异（如实记录）：

1. **`GET /billing/:id/receipts` 照原版无归属校验**（任意登录的 finance/customer 可查任意账单流水）——原版即如此，本批次未自创收紧；若要收紧需先改原版口径再两边同步。
2. **billing 数据为内存实现**：账单/明细/流水/运单快照重启即重置；运单计费时的月结标记取本地同种子副本（customer-service 尚无服务间月结查询接口，与批次 2 同口径），出账校验与名称 JOIN 已走 Feign。
3. **运单状态机未全量迁移**：计费快照目前只有「已揽收 → 已签收」两态（出账所需），转运中/派送中/异常等状态随批次 4 task-service 的揽派流程接入后，由其经内部接口驱动补齐；`createWaybillForOrder` 的真实调用方（揽收登记）也在批次 4。
4. **内部接口 `/internal/**` 无用户角色限制**（服务间调用定位），网关层仍要求登录态；批次 4 接入 task-service 时可再加服务间凭据。

## 批次 4：dispatch + task —— ✅ 完成（2026-11-19）

实现内容：

- **dispatch-service**（`DispatchController` / `AssignEngine` / `DispatchStore` + Feign `TaskClient` / `OrderClient`）：
  - 分配引擎对应 `core/assign.js`：原版决策在 SQL 视图（`v_courier_load` + `v_assign_pickup`）里，按计划改为 Java 查询 + 排序——负载行经 **Feign 从 task-service 取**（数据源 pickup_tasks/employees 归 task），排序键逐项等价原 SQL：`load < capacity` 硬过滤、同片区（地址按 grids 种子序取首个命中区县，同子查询 `LIMIT 1` 的表扫描序）优先 → 在途少优先 → 工号（id）定序；`reasonOf` 人话文案逐字照抄。
  - 对外接口：`GET /flights`（flights 按计划归本服务）、`GET /dispatch/rule`（RULE_SQL 原文逐字展示）、`GET /dispatch/capacity`（运力看板：负载取 task、待派单数取 order、今日已派取 task，util/utilization 的 toFixed 取整口径对齐 JS）、`GET /pickup/candidates`（待派单走视图分支形态——same_area 转 boolean + rn 排名；其他状态退化为 candidatesForAddr 分支——same_area 保持 1/0，两分支差异照原版保留）。
  - 内部接口（计划矩阵 task → dispatch）：`GET /internal/assign/pick`、`GET /internal/assign/candidates`、`GET /internal/flights/:flightNo`。
  - grids 按计划归 customer-service，但其批次 1 未落 grids；本服务先持同种子副本（与 order-service 持 service_areas 副本的既有口径一致），待 customer-service 补 grids 接口后改走 Feign。
- **task-service**（`TaskController` / `TaskStore` / `RoutingEngine` + Feign `DispatchClient` / `OrderClient` / `BillingClient` / `ExceptionClient`）：
  - 独占数据：employees（种子 id 序与 Node 全新库一致：S6001=1、K1001=2、K1002=3、D2001=4、C3001=5、F4001=6、O5001=7，与 auth 账号种子口径一致）、pickup_tasks、delivery_tasks、transit_logs、routing_hubs；负载 = 本服务按 `v_courier_load` 定义实时算（揽收任务 待接单/已接单 计数，派件任务不计，同原版视图），经 `GET /internal/courier-loads`、`/internal/task-stats` 供 dispatch 聚合。
  - 揽收：`GET /pickup/tasks`（INNER JOIN 订单语义：订单不存在则行不出现）、`POST /pickup/assign`（逐单编排 assignOrders：Feign 取订单 → dispatch 定人 → 立刻落任务 + 回写订单 待揽收，下一单负载已 +1，同原版「派完立刻落库」）、`POST /pickup/accept`、`POST /pickup/rollback`（任务 已回滚 + 订单回 待派单）、`POST /pickup`（揽收登记：校验链照原版，经 Feign 调 billing 内部接口生成运单计费快照，再回写订单/任务 已揽收）。
  - 中转：`GET /transit`（拼 operator 名与运单状态/产品）、`GET /transit/suggest`、`POST /transit`（航班校验查 dispatch 主数据；未填下一跳走 RoutingEngine 的 planRoute——`core/routing.js` 逐行翻译，含「降级异类型枢纽时 mode/eta 仍按原 mode 计」「省份未识别时 destCity='未知'」两个原版口径；运单 已揽收 → 经 billing 状态机推 转运中）。
  - 派件：`GET /delivery/tasks`、`GET /delivery/candidates`、`POST /delivery/assign`（逐单：运单须 转运中 → dispatch 按收件地址定人 → 落任务 → 运单推 派送中）、`POST /delivery/sign`（先经 billing 状态机推 已签收 + signed_at，成功后再落本地任务，等价于原版事务回滚结果）、`POST /delivery/fail`（任务 派送异常 + 异常登记 + 运单推 异常）。
  - RoutingEngine 的 classifyZone/provinceOf 是 `core/pricing.js` 的同源本地副本（与 rate-service PricingEngine 同口径）。
- **order-service 补内部接口**：`GET /internal/orders/:id`（整行）、`POST /internal/orders/:id/status`（直接回写 status，原版为直接 UPDATE、无订单状态机）、`GET /internal/orders/stats`（待派单计数）。
- **billing-service 补内部接口**：`GET /internal/waybills/:no`、`POST /internal/waybills/:no/status`（运单状态机 WB_FLOW 逐字照 helpers.js：同状态幂等早返回、非法转移 400 文案 `运单状态不能从「X」变更为「Y」`、可附 signed_at）——批次 3 遗留的「运单状态机未全量迁移」至此补齐，task-service 的中转/派件/签收/异常都经此推进。
- **exception-service 批次 5 提前量（仅一口）**：`ExceptionStore` + `POST /internal/exceptions`（派送异常转异常件的登记，字段/默认值照原版表：status 待处理、severity 中度）；异常列表/定责/理赔仍属批次 5，未展开。

实跑验收（eureka 8761 / gateway 8080 / auth 8100 / order 8101 / customer 8102 / rate 8103 / billing 8104 / dispatch 8105 / task 8106 / exception 8107 共 10 个 JVM 实跑；Node 原版在 /tmp 副本以全新种子库启动于 3001 做同流程对照，原目录零改动；全量 `mvn -B compile` 14 模块 BUILD SUCCESS、`mvn -B package` 成功；同一验收脚本双跑 **39/39 项逐项深比对一致**，时间戳字段按格式归一后比对）：

- [x] **派单一致性（硬指标）**：同 6 单（滨江/浦东同片区、西湖/南山/朝阳无片区、滨江∩西湖双命中取首网格）一次批量分配，两边逐单一致——A1→快递员小李（同片区，load_before 0）、A2→快递员小王（同片区，0）、A3→小李（跨区，1）、A4→小王（跨区，1）、A5→小李（跨区，2）、A6→小李（同片区，3），same_area/reason 文案逐字一致；候选列表（视图分支与派件候选）排名、负载、reason 全一致；运力看板派单前后两边一致（pending 6→0、today_assigned 6、负载分布一致）。
- [x] **回滚再派**：第 7 单派给小李（load_before 4）→ rollback 后订单回 待派单、任务 已回滚（rollback_reason=超时未接单）→ 再派仍小李（load_before 4），两边一致。
- [x] **全流程走通**：下单 → 派单 → 接单（重复接单 400 文案一致）→ 揽收（运单号两边同为当日流水序，费 **23.47** / **18.88**，计费明细文本逐字一致）→ 中转建议（同城直发、上海→HGH 空运枢纽，两边 JSON 全等）→ 中转登记（带航班 CA1832 + 安检、坏航班 400、缺节点 400 文案一致；运单推 转运中）→ 派件候选/分配（两单分别派小李/小王，与原版一致）→ 签收（运单 已签收 + signed_at，重复签收 400 一致）→ 派送异常（运单推 异常、任务 派送异常）→ **出账联动**：签收运单被 billing 汇总，`POST /billing/generate` 两边同为 bill_no `BILL2611190001`、金额 **23.47**、明细 1 条。
- [x] **角色与越权（两边一致）**：finance 看揽收任务 403、claims 看中转 403、customer 看员工 403、ops 看运力看板 403、customer 看派件任务 403（均为网关揽派前缀 gate）；courier 看揽收任务 200 且为全量 8 条——**原版 dispatch.js 对 courier 无任务归属过滤**（accept/sign 亦不校验归属），与批次简报「courier 只能看自己的任务」不一致，本批次按「逐项照原版、不自创口径」保留原版行为并在此明示：如需按归属收紧，应先改原版口径再两边同步。

遗留/差异（如实记录）：

1. **轨迹 events 未写**：揽收/中转/签收/异常的 event() 轨迹归 track-service（批次 6），本批次不写，与批次 2 口径一致；RoutingEngine 与 transit 响应里的 route 信息已备好，批次 6 接入时补写。
2. **跨服务无分布式事务**：派单/揽收/签收等多写链按「先经状态机校验、再落本地行」的顺序串行执行，失败语义等价于原版回滚结果，但极端情况下（如进程在两步之间崩溃）可能留下单边状态，靠状态对账兜底，与批次 3 口径一致。
3. **数据仍为内存实现**：employees/routing_hubs 为种子常量，任务/中转/派件数据重启即重置，落库不在本批次范围。
4. **冷启动时序坑（运维）**：本机 9~10 个 JVM 并发冷启动时单个服务最长约 150 秒才就绪，LoadBalancer 实例缓存未刷新时首个 Feign 调用可能短暂降级（本次验收首跑出现一次 order→rate 冷启动降级，预热后消失）；演示/验收前应先等全部服务注册完成并预热一次试算。

## 批次 5：exception + claim —— ✅ 完成（2026-11-19）

实现内容：

- **exception-service**（新增 `ExceptionController` / `ClaimEngine` / `Assessor` / `WaybillGateway` + Feign `BillingClient` / `TaskClient` / `ClaimClient`，扩 `ExceptionStore` 与 `InternalExceptionController`）：
  - `ClaimEngine` = 原版 `core/claim-engine.js` 逐项翻译：定责三分支（拒收→非承运责任、丢失→全责、破损→按安检分主责/已过安检）、折损四档（0.2/0.4/0.7/1.0）、已保价 = 保价额×系数（上限=保价额）、未保价 = min(运费×3, ¥300) 封顶、证据缺失标记、>500 二级审批标记；规则文案与金额插值（JS 数字最小化表示、toFixed(2) 口径）逐字对齐。
  - `Assessor` = `assessForException`：运单（insured/fee）经 **Feign ← billing** 内部接口取；安检判定经 **Feign ← task** 新增的 `GET /internal/transit/security-passed`（查 transit_logs.security=1），再以本服务事件日志含「安检」描述兜底（原版双口径）。
  - 对外接口逐项照 `services/claim.js`：`GET /exceptions`（LEFT JOIN 理赔单语义：无理赔出一行 null、多张理赔出多行）、`GET /exceptions/:id`（异常单+运单+理赔单+理赔流程事件）、`POST /exceptions`（校验链顺序与文案照原版；运单在 已揽收/转运中/派送中 时经 billing 状态机推「异常」，失败以补偿删除等价原版事务回滚）、`GET /exceptions/:id/assess`（附 severity_options）、`POST /exceptions/:id/close`（默认理由分拒收/其他两支；先推运单「已关闭」成功后再落本地关闭，等价回滚语义）。
  - 内部接口（供 claim-service）：`GET /internal/exceptions/:id`、`GET /internal/exceptions/:id/assess`、`POST /internal/exceptions/:id/status`（severity 落档与 status 流转回写）、`POST /internal/events`（理赔流程写轨迹事件）。批次 4 的 `POST /internal/exceptions` 保持不变。
- **claim-service**（新增 `ClaimController` / `ClaimStore` / `InternalClaimController` + Feign `ExceptionClient` / `BillingClient`）：
  - `GET /claims`（JOIN 异常单的 waybill_no/type/exception_status，经 Feign 逐单取）、`POST /claims`（异常状态校验→重复理赔校验→经 exception 内部试算取核定→manual 改编规则→金额>0 与超上限校验，文案逐字一致；写链：severity 落档→本地落单→异常转待审批→理赔受理事件）、`POST /claims/approve`（驳回：理赔单已驳回+异常回待处理+驳回事件；赔付：**先经 billing 状态机推运单「已关闭」**（唯一可业务失败步，失败原样透传文案且不落任何状态），成功后再落理赔单已赔付/paid_at、异常转已理赔、赔付事件——等价原版关单事务）。
  - 定责引擎只在 exception-service 一份，claim 发起理赔时经内部试算接口取结果，避免两处口径漂移；`GET /internal/claims[?exception_id=]` 供 exception 侧 JOIN（按 id 倒序）。
- **task-service 补内部接口**：`GET /internal/transit/security-passed?waybill_no=`（批次 5 定责取数）。

实跑验收（eureka 8761 / gateway 8080 / auth 8100 / order 8101 / rate 8103 / billing 8104 / dispatch 8105 / task 8106 / exception 8107 / claim 8108 共 10 个 JVM 实跑；Node 原版在 /tmp 副本以全新种子库启动于 3001 做同场景对照，原目录零改动；全量 `mvn -B compile` 14 模块 BUILD SUCCESS、`mvn -B package` 成功；同一验收脚本双跑 **52 项标注记录，归一化（时间戳/令牌）后逐项深比对 0 差异**；验后已停全部 JVM 与临时 Node，端口均释放）：

- [x] **造数全链路**：6 张运单同流程双跑（下单→派单→接单→揽收→中转→派件→签收/派送异常），运单号两边同为 `8826111900001`–`…006`；其中 1 张由派送异常经 task→exception 内部登记自动生成异常件（批次 4 提前量），本批理赔全流程在其上走通并关单。
- [x] **定责试算对照（多组全一致）**：破损+保价 2000+严重 → 1400（cap 2000、二级审批标记 true、有照片 need_evidence false）；默认中度 → 800；破损+已过安检（transit security=1）→ 标签「承运方责任（已过安检，破损发生在承运环节）」、全损 → 1000=cap；丢失+未保价（运费 14.77）→ cap 44.31（运费×3）、中度赔付 17.72；拒收 → liable=customer、amount 0；severity_options 四档与原版一致。
- [x] **理赔全流程对照**：受理（异常转待审批、severity 落档、理赔受理事件文本含「核定 ¥1400.00」）→ 审批赔付（理赔单已赔付+paid_at、异常已理赔、运单经状态机 已签收/异常→已关闭、赔付事件文本一致）；驳回流：异常回待处理、再发起不被已驳回单阻挡、二次审批赔付成功。
- [x] **边界逐条实测（两边一致）**：manual 1500 超 cap 1000 → 400 `核定金额 ¥1500 超过上限 ¥1000`；重复发起（已有待审批）→ 400 `该异常单已存在待审批或已赔付的理赔单`；重复审批 → 400 `理赔单已已赔付，不能重复审批`；拒收自动核定为 0 → 400 非承运责任文案，manual 50 成功且 rules 追加「人工核定调整至 ¥50」（cap=0 不触发上限校验，同原版）；对已关闭运单登记异常 → 400 `运单已关闭，不能登记异常`；对已关闭异常单发起理赔 → 400；拒收直接关闭流（默认理由「收件人拒收，原件退回」、运单已关闭、重复关闭 400）；登记校验三连（缺参/非法类型/运单不存在 404）与异常单/理赔单不存在 404 文案全一致。
- [x] **角色门（两边一致）**：finance 看异常列表 403、courier 发起理赔 403、dispatcher 看理赔列表 403（均为网关理赔前缀 gate，文案同原版）、无 token 401、admin 200；理赔前缀放行 claims 后服务内无二次 allow（原版 claim.js 路由内本无 allow 调用）。

遗留/差异（如实记录）：

1. **详情 events 只含理赔流程事件**：`GET /exceptions/:id` 的 events 目前只含 claim.js 口径写出的事件（异常登记/异常关闭/理赔受理/理赔驳回/理赔赔付，本服务事件日志留痕，理赔侧经内部接口写入）；Node 返回该运单全轨迹（含下单/揽收/中转/签收/派送异常等其他环节事件）。全量轨迹统一归批次 6 track-service（与批次 4 口径一致）；本次验收对 events 按理赔流程节点过滤后比对，全一致。
2. **详情 waybill 字段集为超集**：Java 返回 billing 内部运单 JSON（含 order_no/recv_*/fee_snapshot 等批次 3/4 既定拼装字段），Node 返回 waybills 原始行；核心字段（waybill_no/status/insured/fee/product）逐项一致。
3. **试算的降级口径**：assess 为只读试算，billing/task 不可用时按 insured/fee=0、未过安检降级计算（原版单体同库读取无此状态）；写路径（登记/关闭/审批）遇运单服务不可用返回 503 degraded 而非硬写。
4. **数据仍为内存实现**：exceptions/claims/事件日志重启即重置；经批次 4 内部接口登记的异常件 severity 默认「中度」（同原版列默认）。理赔关单的跨服务多写链无分布式事务，按「可失败步先行 + 状态机对账」处理，与批次 3/4 口径一致。

## 批次 6：track + report + notify —— ✅ 完成（2026-11-19）

实现要点（路径与返回格式与原版逐字一致，轨迹 events 写入在本批收口）：

- **track-service（新启用）**：持有 events 存储（内存），`POST/GET /internal/events` 供各服务写/读轨迹；`GET /tracking/:no` 照原版 `order.js` 轨迹查询（按运单号或订单号定位、客户只能查自己的 403 `只能查询自己的运单`、查无此号 404 `未找到该运单号或订单号的跟踪记录`、on_time 由 signed_at 与 promise_time 字符串比较得出）；运单/订单本体经 Feign 取自 billing/order 内部接口。**events 写入点按原版 `helpers.event()` 逐点补齐**（共 10 处）：order 下单受理（订单维度，文案含地址/预估费/时段）与订单取消、task 揽收任务已分配/已回滚/揽收点/中转节点/目的网点/收件地址（迟到标记同原版字符串比较）/派送异常（desc 用原始 body 值，与原版在缺省填充前的取值一致）、exception 异常登记/关闭、claim 理赔受理/驳回/赔付；写入一律经 Feign → track `POST /internal/events`，best-effort 失败不阻断主流程（exception→track 的回写除外：批次 5 的本地事件日志仍同步必写，转发 track 为尽力而为）。**批次 5 遗留收口**：exception 详情的 events 改为优先读 track 全轨迹（运单维度，与原版同口径，不含订单维度事件），track 不可用时回落本地日志；Assessor 的「已过安检」判定增加 track 事件回查兜底。
- **order-service 补批次 2 遗留**：`POST /orders/:id/cancel` 照原版（只能取消自己的 403、已取消 400 `该订单已取消`、状态门 [待派单，待揽收]、取消前经 task 内部接口回滚待接单揽收任务，task 不可用返回 503 degraded 且不落取消——等价原版单事务回滚）；`GET /internal/orders/by-no/:orderNo` 供 track 按订单号定位。
- **report-service（新启用）**：`KpiEngine` 为原版 `core/kpi.js` computeKpi 逐项翻译（归一化在含被剔除者在内的全员行上做 min-max、max==min 取 60、负向指标 100−s、MIN_SAMPLE=3 小样本剔除、权重归一与排序、等级 A≥85/B≥70/C≥55/D、breakdown 贡献值、JS 稳定排序语义）；原始 KPI 行由 task-service `GET /internal/kpi-rows` 计算（派件任务↔运单经 billing 内部接口 JOIN，等价原版 SQL），其余聚合（汇总/五类数据集/工作台）由 report 经各服务内部列表接口取数。接口：`GET /performance`（ops/finance，admin 恒过）、`GET /reports/summary`、`GET /reports/preview`（五类数据集 + 未知类型 400）、`GET /reports/export`（CSV 带 BOM + 原版 csvCell 引号规则；XLS 为原版 HTML 表格模板逐字复刻；文件名日期戳用 UTC 日期，同原版 `toISOString().slice(0,10)`）、`GET /board`（任意登录角色）。period 口径照原版：绩效 period 仅回显不做日期过滤（computeKpi 本无日期过滤），汇总/数据集按月过滤（by_status 例外，无月份过滤，同原版 SQL）。
- **notify-service（新启用，计划横切项）**：原版无通知模块、无短信/推送通道，本服务按计划做**内部记账型通知**：`POST /internal/notifications` 记录 + `GET /notifications` 查询（任意登录角色）；生产方非阻断接入三处：order 下单成功（order_created）、task 签收成功（waybill_signed）、claim 审批赔付（claim_paid），字段 {id,type,ref_no,content,recipient,created_at,read}。不自创外部通道。

实跑验收（eureka 8761 / gateway 8080 + 12 个业务服务共 14 个 JVM 全量实跑；Node 原版在 /tmp 副本以全新种子库启动于 3001 做同场景对照，原目录零改动；全量 `mvn -B compile` 与 `mvn -B package -DskipTests` 14 模块 BUILD SUCCESS；同一验收脚本双跑同一场景：4 张订单全流程（下单→派单→接单→揽收→中转→派件分配→签收 3 张 + 第 4 张派送异常→自动异常件→定责→理赔→赔付关单）+ 李四当月出账 + 取消流（自有取消/重复取消 400/取消他人 403），共 **51 项标注记录归一化（时间戳→DT、导出文件名日期→DATE）后逐项深比对 0 差异**；验后已停全部 JVM 与临时 Node，端口均释放）：

- [x] **轨迹对照（逐条一致）**：按运单号查 wb1 全链 6 节点「下单受理→揽收任务已分配→揽收点→杭州市分拣中心→目的网点→收件地址」节点名/顺序/desc 文案与原版逐条相同；按订单号查同一运单结果一致；理赔支线 wb4 共 8 节点（含派送异常→理赔受理→理赔赔付）逐条一致；已取消订单轨迹含「订单取消」节点一致；异常详情 events 已对齐原版全轨迹（6 条运单维度事件，与 Node 深比对一致，批次 5 遗留关闭）；客户查他人运单 403 文案、查无此号 404 文案、无 token 401 两边一致。
- [x] **绩效对照（逐项一致）**：同组数据双跑排名与分数全同——快递小李 总单 4（揽 2/派 2）综合 78 分 B 级（breakdown 准时 60×0.35=21、签收 100×0.3=30、揽收 60×0.2=12、异常 100×0.15=15），快递小王 总单 3（揽 2/派 1/异常 1）33 分 D 级；两人均 ≥MIN_SAMPLE=3 未被剔除，权重/方法说明/min_sample 字段一致；小样本剔除规则由 KpiEngine 逐项翻译并在代码层面对照原版（本场景无 <3 样本员工，剔除分支以原版种子员工不出现在任务数据时不入榜的行为间接验证：分拣员小吴等非 courier 不入榜两边一致）。角色门：courier 看绩效 403 文案一致，ops/admin 200。
- [x] **报表与导出对照（逐行一致）**：summary 全字段一致（revenue 99.86、signed 3、on_time_rate 100、claims_amount 1400、bill_amount 22.16、by_product/by_status/by_exception 分组计数与金额一致）；五类 preview（performance/waybills/bills/exceptions/transit）数据集逐行一致、未知 type 400 文案一致；五类 CSV 导出在时间字段归一化后**逐字节一致**（含 BOM、引号规则、文件名 `XX报表_YYYY-MM-DD.csv` 形态）；XLS 导出 Content-Type 与 HTML 表格正文归一化后一致。
- [x] **工作台对照**：`/board` 全字段一致（todayPickups 4、todaySigns 3、pendingOrders 1、openExceptions 0、unpaidBills 1、revenue 99.86、onTimeRate 100），courier 角色可读（200）与原版一致。
- [x] **notify 核查（Java 新增，无 Node 对照）**：场景跑完后通知记录恰为 order_created ×6（订单号 SO26111900001–006）、waybill_signed ×3（运单 …001–003）、claim_paid ×1（运单 …004），类型/ref_no 与业务单据逐一对应，生产方失败不阻断主流程。

遗留/差异（如实记录）：

1. **summary 分组并列序为复刻 SQLite 未定义序**：原版 `GROUP BY ... ORDER BY cnt DESC` 对 cnt 并列组的出序属 SQL 未定义行为，实测 SQLite（3.53.4）sorter 为「并列时按分组键 UTF-8 字节序倒序」（已用三组并列 + 不同录入顺序复验，与录入顺序无关）；Java 侧照此复刻（by_product/by_status/by_exception 同规则）。若原版换 SQLite 版本或数据量大到触发多路归并，该序理论上可能漂移——属原版行为本身不确定，非契约差异。
2. **events 写入由原版同库事务改为跨服务 best-effort**：原版 event() 与业务写在同一 SQLite 事务内（强一致）；Java 侧经 Feign 异步语义尽力写入，track 不可用时事件会丢（主流程不受影响），极端情况下轨迹可能缺条。理赔→异常件状态等强一致环节仍保留批次 5 的本地必写口径。生产化需引入消息队列/事务发件箱，留待后续。
3. **数据仍为内存实现**：events/通知/KPI 权重（kpi_weights 种子 pickups .20/deliveries .30/on_time .35/exception .15，与原版同种子）重启即重置；报表聚合每次实时经内部接口取数，无缓存（同原版直查口径）。
4. **批次 2 遗留全部清零**：订单取消接口已在本批补齐；至此原计划内全部业务接口已迁完，剩余批次 7 为前端联调与总验收（44 条 e2e 经网关全量回归）。


## 批次 7：总验收（e2e 经网关 + 前端 14 页联调） —— ✅ 完成（2026-11-19）

验收前提：14 个 JVM 全量启动（start-all.sh：Eureka 先行 → 12 服务 + 网关并行 → 等注册齐），**Node 原版全程未启动**（验收前后均核验 3001 无监听）。

**为总验收补齐的 Java 侧入口（属迁移未尽项，非为迁就断言改逻辑）**：

1. **网关 /api 正式入口**：批次 0–6 经网关验证用的是 `/gw/<服务>/**` 调试入口；原版 server.js 的正式面是 `/api` 扁平挂载。本批在 gateway 新增与原版挂载表一一对应的 `/api/**` 路由（StripPrefix=1；其中 `/api/pickup/candidates` 归 dispatch-service，须先于 `/api/pickup/**`（task-service）声明；`/api/waybills` 归 billing、`/api/service-areas` 归 order、`/api/board` 与 `/api/performance`、`/api/reports` 归 report）；`JwtAuthGlobalFilter` 增加 `/api` 前缀归一化（gate 角色表作用于归一后的服务内路径）与 `/health` 公开路径。`/gw/<服务>/**` 调试入口保留不变。
2. **gateway 新增 `HealthController` 实现 `GET /api/health`**：形态与原版一致 `{ok:true,data:{ts,services:[...]}}`，services 仍为原版五大业务域（订单/计费/揽派调度/异常理赔/绩效统计）；与原版静态写死的区别是内容按各域背后微服务在 Eureka 的注册状态聚合（全部有 UP 实例才计入），全量在线时同为 5 项。
3. **order-service 补迁 `GET /service-areas`**（原版 order.js 有、前端 Orders 页在用，前批次未覆盖）：返回 `{...checkArea(addr), areas:[service_areas 全表 8 行种子]}`，判定逻辑复用批次 2 已译的 OrderData.checkArea。

**e2e 总验收结果**：

- 原 `e2e.js` 全文含 44 处 `assert(`（1 处为函数定义、43 处调用点），全新状态下运行计数恰为 **44 条**，与计划口径一致。
- 副本 `e2e-gateway.js` 放在 aped-cloud 侧，与原文件 diff **仅第 7 行** BASE 默认值 `http://127.0.0.1:3001/api` → `http://localhost:8080/api`，断言逻辑一字未改；原文件未动。
- 预热说明：服务刚注册齐时网关健康曾短暂只报 4 域（order 注册缓存未刷新，约 40 秒后恢复 5 域）；首个试算出现 1 次冷启动降级（批次 4 已知坑），重试即正常——均属已记录的冷启动时序，预热后正式跑。
- **正式跑一次全过：`E2E PASS（断言 44 条全部通过）`**，无失败、无跳过；全过输出保存在 `e2e-final-run.log`。全程未修改任何断言，未为迁就断言改动业务逻辑（仅补齐上述入口与缺失接口各一处）。

**前端 14 页联调结果**（原前端 `dist` 构建产物——v3 版 bundle `index-Bjkzeq1P.js`——只读复制到 /tmp 静态托管，`/api` 反代到网关 8080，等价原 vite proxy 部署形态；前端源码与构建产物零改动）：

- 静态资源：index.html / JS（200 KB）/ CSS / 画廊图（Dashboard 用）均 200；bundle 内 13 条命名路由 + 根路由 `/` 齐全（14 页）。
- 逐页以对应角色 token 经代理实调其数据接口，**14 页全过**：登录页（7 个角色登录成功）；工作台 `/board` + `/health`（5 域）；我的（客户本人档案 + 本人账单）；订单（列表/客户/服务范围判定+全表）；调度（运力看板/派单规则/订单）；揽收（任务 ≥2）；中转（中转单/员工/航班/运单/路由建议）；派送（派件任务/运单）；轨迹（运单轨迹 ≥5 条）；异常理赔（异常列表 ≥2/详情）；客户（3 条/详情）；计费账单（账单/运价卡/计费规则/账单详情）；绩效（当月绩效行 ≥1）；报表（汇总/预览/CSV 导出含运单号）。
- 验证方式如实说明：本环境无真实浏览器，未做页面渲染与点击的真机验证；结论以「静态资源可加载 + 每页数据接口经网关实调返回正常」为准。

**收尾**：全量 `mvn -B compile` 与 `mvn -B package -DskipTests` 均 BUILD SUCCESS（父 pom + 14 模块）；一键脚本 `start-all.sh` / `stop-all.sh` 已落盘；验收后已停全部 JVM 与联调代理，8761/8080/8100–8111/3001/8899 端口全部释放。未推 GitHub（等用户决定）。

**已知口径清单（汇总各批次遗留中仍有效的条目，即最终状态）**：

1. **数据为内存实现**：全部业务数据（账号/订单/运单/账单/任务/异常/理赔/轨迹/通知/KPI 权重等）存各服务内存，任一服务重启即重置该服务数据（种子自动恢复）；落库（计划中的 H2/JPA + MySQL 配置档）未实施，是后续生产化的第一步。
2. **网关不查退出黑名单**：已退出的 token 在过期（12 小时）前仍可通过网关到达非 auth 服务，靠下游按身份头做范围控制兜底（auth 直连 `/auth/me` 会校验黑名单）；生产化需共享吊销存储或缩短 TTL。
3. **JWT 密钥为本地开发默认值**（`aped.jwt.secret`，auth 与 gateway 一致），生产部署必须经环境变量覆盖。
4. **轨迹 events 为跨服务 best-effort 写入**：track 不可用时轨迹可能缺条（主流程不受影响）；exception 的本地事件日志仍同步必写。生产化需消息队列/事务发件箱。
5. **跨服务多写链无分布式事务**：按「可失败步先行 + 状态机校验 + 状态对账」等价原版单事务结果；进程在两步之间崩溃可能留下单边状态。
6. **与原版一致、未收紧的宽松口径**：courier 无任务归属过滤（任务列表全量可见）；`GET /billing/:id/receipts` 无归属校验；内部接口 `/internal/**` 无用户角色限制（网关层仍要求登录态）。如需收紧应先改原版口径再两边同步。
7. **报表 summary 分组并列序**复刻的是 SQLite 的未定义排序行为，理论上随 SQLite 版本/数据量可能漂移（原版行为本身不确定）。
8. **冷启动时序**：14 个 JVM 并发冷启动单个最长可达约 150–250 秒；注册/负载均衡缓存未刷新时首个 Feign 调用可能短暂降级、`/api/health` 可能短暂少报业务域，须等注册齐并预热后（start-all.sh 已含等待与健康检查）再正式使用。
9. **notify 为内部记账型通知**（原版无通知模块、无外部通道），只有记录与查询，无短信/推送。
10. **个别跨服务取数仍走本地同种子副本**：order 查客户月结标记、billing 出账月结标记（customer-service 尚无服务间月结查询接口；billing 的出账校验与名称 JOIN 已走 Feign）。
11. **`/api/health` 与原版的差异点**：原版静态返回 5 域，Java 版按 Eureka 聚合——某域背后任一服务未注册时该域暂不出现在列表中（全量在线时同为 5 项）。
