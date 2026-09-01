# auth-service

**함께하개의 인증 서비스입니다.** 계정을 만들고, 로그인시키고, 토큰을 발급합니다.
도메인 서비스 14개 중 **첫 번째로 만들어진 서비스**이며, 다른 서비스들이 전부
이 서비스가 만든 토큰을 전제로 동작합니다.

<br><br>

---

## 0. 이 서비스가 하는 일

```
브라우저  ──▶  게이트웨이  ──▶  auth
                   │             │
                   │             ├──▶  PostgreSQL  auth_db     계정 · 로그인 이력 · outbox
                   │             ├──▶  Redis                  리프레시 토큰 · 인증 코드 · OAuth state
                   │             ├──▶  Kafka                  account.created · account.withdrawn 발행
                   │             ├──▶  Gmail SMTP             인증 코드 메일
                   │             └──▶  Google OAuth           소셜 로그인
                   │
                   └──▶  쿠키의 JWT 를 검증하고 X-User-Id · X-User-Role 을 붙여 넘김
                         auth 는 토큰을 만들고, 게이트웨이는 토큰을 확인함
```

> **auth 는 다른 도메인 서비스를 한 번도 부르지 않습니다.** 닉네임처럼 남의 데이터가
> 필요한 것은 응답에 담지 않고, 프론트가 그 서비스를 따로 부릅니다.

---

**숫자로 보면 이렇습니다.**

| | 개수 | 어디에 |
|---|---|---|
| API | **17개** | `/api/v1/auth/**` 15개 + `/api/v1/admin/accounts/**` 2개 |
| 테이블 | 2개 + outbox | `account` · `refresh_token_log` |
| Redis 키 종류 | 9종 | 리프레시 토큰 · 인증 코드 4종 · 발송 제한 2종 · OAuth · 시도 횟수 |
| 발행하는 이벤트 | 2개 | `account.created` · `account.withdrawn` |
| 받는 이벤트 | 0개 | 리스너가 없습니다 |
| 부르는 바깥 시스템 | 2개 | Google OAuth · Gmail SMTP |
| 서비스 클래스 | 11개 | [5-2](#5-2-서비스-클래스-11개--누가-무엇을-하나) |
| 에러 코드 | 16개 | [3-9](#3-9-에러-코드-16개) |

---

**하는 일을 사용자 입장에서 보면 이렇습니다.**

```
가입          이메일 인증  →  회원가입  →  (user 서비스가 프로필을 만듦)
로그인        이메일 + 비밀번호   또는   구글 계정
로그인 유지    액세스 토큰 30분  →  만료되면 리프레시 토큰으로 갱신 (14일)
비밀번호       변경 (로그인 상태)   /   재설정 (잊었을 때, 메일 인증)
탈퇴          메일 인증  →  계정 상태를 바꾸고 다른 서비스에 알림
```

<br><br>

---

### 이 문서를 읽는 순서

| 지금 하려는 일 | 볼 곳 |
|---|---|
| 일단 띄워서 로그인이 되는지 보고 싶다 | [1장](#1-로컬에서-띄우기) |
| 토큰이 뭔지, 쿠키가 왜 두 개인지 모르겠다 | [2장](#2-인증이-어떻게-도는가) |
| API 를 부르려는데 요청·응답 형태를 모르겠다 | [3장](#3-api-17개) |
| 테이블·Redis 키·이벤트가 뭐가 있는지 | [4장](#4-데이터) |
| 코드를 고치려는데 어느 파일인지 모르겠다 | [5장](#5-코드-구조) |
| 설정값이 어디서 오는지 | [6장](#6-설정값) |
| 관리자를 지정하거나 이벤트를 다시 보내야 한다 | [7장](#7-운영) |
| "왜 이렇게 만들었지" 가 궁금하다 | [8장](#8-왜-이렇게-만들었나) |
| 뭔가 안 된다 | [9장](#9-막히기-쉬운-자리) |
| 모르는 말이 나온다 | [11장](#11-용어) |

> **공통 규칙은 이 문서에 없습니다.** 4계층 구조·공통 모듈·설정 저장소·Docker 환경은
> [`service-template` README](https://github.com/paw-trail/service-template) 에 있고,
> 이 문서는 **auth 에만 해당하는 것**을 적습니다.

<br><br>

---

### 진행 상태

```
#1   스키마 V20                      ✅
#3   엔티티 + 리포지터리               ✅
#5   인증 기반 — 보안 설정 · 토큰 발급  ✅
#7   회원가입 · 로그인                 ✅
#9   이메일 인증 · 비밀번호 재설정      ✅
#11  refresh · logout               ✅
#13  /me · 비밀번호 변경              ✅
#15  소셜 로그인 (구글)               ✅
#17  탈퇴 + 관리자 outbox            ✅
#19  패키지 구조 정리                 ✅
```

**API 17개가 전부 구현되고 실물 검증까지 끝났습니다.** 남은 것은
[10장](#10-아직-안-한-것) 에 있습니다.

<br><br>

---

## 1. 로컬에서 띄우기

**공통 환경(JDK · Docker · IntelliJ · GitHub 토큰)은 갖춰져 있다고 봅니다.**
없다면 `service-template` README 3장을 먼저 봅니다.

<br><br>

---

### 1-1. auth 만의 준비물

다른 서비스와 달리 **바깥 시스템 둘을 씁니다.** 그래서 코드 밖에서 먼저 받아 와야
하는 값이 있습니다.

| 준비물 | 어디서 | 왜 |
|---|---|---|
| RS256 개인키 | 팀장이 만들어 전달 | 토큰에 서명합니다. **팀에서 하나만 씁니다** |
| Gmail 앱 비밀번호 | 팀장이 전달 | 인증 코드 메일을 보냅니다 |
| 구글 클라이언트 시크릿 | 팀장이 전달 | 소셜 로그인 |
| 구글 테스트 사용자 등록 | 팀장에게 내 구글 주소를 알림 | 앱이 Testing 상태라 등록된 사람만 로그인됩니다 |

> **셋 다 사람이 전달합니다.** 저장소 어디에도 없고 config 저장소에도 없습니다.
> 값을 받으면 **환경변수에 넣고 "넣었다" 만 알리면 됩니다.** 채팅에 붙여넣지 않습니다.

---

**RS256 개인키가 하나여야 하는 이유입니다.**

```
auth  ──▶  개인키로 서명한 토큰 발급
                  │
                  ▼
게이트웨이  ──▶  공개키로 서명을 검증        공개키는 config 저장소에 있음

개인키와 공개키는 한 쌍이라 다른 개인키로 만든 토큰은 게이트웨이가 거부함
  → 팀원마다 다른 키를 쓰면 서로의 토큰이 안 통함
```

로컬 개발용 키이고 **배포할 때는 새 쌍을 만듭니다.** [7-4](#7-4-키-페어를-새로-만들-때) 참고.

<br><br>

---

### 1-2. 환경변수

IntelliJ 실행 구성의 **Environment variables** 에 넣습니다.
넣는 방법은 `service-template` README 4-4 에 있습니다.

| 이름 | 값 | 무엇에 쓰나 |
|---|---|---|
| `DB_HOST` | `localhost` | 로컬 PostgreSQL |
| `SERVICE_DB_PASSWORD` | `infra/.env` 와 같은 값 | `auth_svc` 계정 비밀번호 |
| `AUTH_JWT_PRIVATE_KEY_B64` | 전달받은 값 | 토큰 서명 (Base64 한 줄) |
| `AUTH_MAIL_PASSWORD` | 전달받은 16자리 | Gmail SMTP |
| `AUTH_OAUTH_GOOGLE_CLIENT_SECRET` | 전달받은 값 | 구글 토큰 교환 |

```
DB_HOST=localhost;SERVICE_DB_PASSWORD=...;AUTH_JWT_PRIVATE_KEY_B64=...;AUTH_MAIL_PASSWORD=...;AUTH_OAUTH_GOOGLE_CLIENT_SECRET=...
```

---

**빠뜨리면 이렇게 됩니다.**

| 빠진 것 | 증상 |
|---|---|
| `DB_HOST` | `UnknownHostException: ${DB_HOST}` — 기동 실패 |
| `SERVICE_DB_PASSWORD` | `password authentication failed for user "auth_svc"` |
| `AUTH_JWT_PRIVATE_KEY_B64` | 기동 실패. `JwtProperties` 가 빈 값을 거부합니다 |
| `AUTH_MAIL_PASSWORD` | **기동은 됩니다.** 메일을 보낼 때 `535 Authentication failed` |
| `AUTH_OAUTH_GOOGLE_CLIENT_SECRET` | **기동은 됩니다.** 구글 콜백에서 `invalid_client` |

> 메일과 구글은 **기동 시점에 검증하지 않습니다.** 값이 틀렸는지는 실제로 부를 때
> 드러나므로 [1-4](#1-4-테스트-계정-만들기) 에서 한 번씩 확인합니다.

---

**Gmail 앱 비밀번호는 띄어쓰기를 지웁니다.**

구글이 보여 주는 값은 `abcd efgh ijkl mnop` 처럼 4자리씩 띄어 있는데
**붙여서 16자리로 넣어야 합니다.** 그대로 넣으면 인증에 실패하고 오류는
*"비밀번호가 틀렸다"* 로만 나옵니다.

<br><br>

---

### 1-3. 띄우고 확인하기

```
① docker compose up -d                 infra 저장소에서 (Kafka · Redis · PostgreSQL · 플랫폼 3개)
        │
        ▼
② IntelliJ 실행 구성에 환경변수 4개      AUTH_JWT_PRIVATE_KEY_B64 · AUTH_MAIL_PASSWORD
        │                             AUTH_OAUTH_GOOGLE_CLIENT_SECRET · SERVICE_DB_PASSWORD
        ▼
③ AuthApplication 실행                 30초 안팎
        │
        ├──▶  설정 서버에서 auth-service.yml 을 받음
        ├──▶  Flyway 가 V20 ~ V23 을 실행해 테이블을 만듦
        └──▶  유레카에 auth-service 로 등록
        │
        ▼
④ curl localhost:8081/actuator/health          {"status":"UP"}
        │
        ▼
⑤ curl localhost:8080/api/v1/auth/me            401 이면 게이트웨이까지 이어진 것
                                                (토큰이 없어서 401 — 정상)
```

---

**① 컨테이너를 띄웁니다.**

**macOS · Windows 공통**

```bash
cd <infra 경로>
docker compose up -d
docker compose ps           # 전부 (healthy) 인지
```

기본 프로파일에 `db` 가 들어 있어 PostgreSQL 도 함께 뜹니다.
`auth_db` 와 `auth_svc` 계정은 초기화 스크립트가 만들어 둡니다.

---

**③ 기동 로그에서 볼 것입니다.**

```
The following 1 profile is active: "local"                     ← local 이어야 함
Flyway: Successfully applied 4 migrations to schema "public"     ← V20 ~ V23 (처음 한 번)
Registered instance AUTH-SERVICE/... with status: UP             ← 유레카 등록
Tomcat started on port 8081                                      ← 포트
```

> `Flyway: Schema "public" is up to date` 면 두 번째 기동입니다. 정상입니다.

---

**④⑤ 두 포트로 확인합니다.**

**macOS**

```bash
curl http://localhost:8081/actuator/health          # auth 직접
curl http://localhost:8080/api/v1/auth/me           # 게이트웨이 경유
```

**Windows (PowerShell)**

```powershell
curl.exe http://localhost:8081/actuator/health
curl.exe http://localhost:8080/api/v1/auth/me
```

| 부른 곳 | 정상 응답 | 뜻 |
|---|---|---|
| 8081 `/actuator/health` | `{"status":"UP"}` | auth 가 떴습니다 |
| 8080 `/api/v1/auth/me` | `401` + `{"code":"AUTHENTICATION_FAILED"...}` | 게이트웨이가 auth 를 찾았고, 토큰이 없어 막은 것 |
| 8080 `/api/v1/auth/me` | `503` | 게이트웨이가 유레카에서 auth 를 못 찾음. **30초 기다렸다 다시** |
| 8080 `/api/v1/auth/me` | `404` | 라우트가 없음. config 의 `gateway-server.yml` 확인 |

> **8080 과 8081 을 구분하는 것이 이 서비스에서 특히 중요합니다.**
> 인증이 필요한 API 는 게이트웨이가 헤더를 넣어 줘야 동작하므로
> **8081 로 직접 부르면 언제나 401** 입니다. [9장](#9-막히기-쉬운-자리) 참고.

<br><br>

---

### 1-4. 테스트 계정 만들기

**가입은 이메일 인증을 거쳐야 합니다.** 실제 메일을 받아 코드를 넣는 것이 정석이고,
급할 때는 Redis 에 인증 표시를 직접 넣어 건너뜁니다.

---

**정석 — 메일로 인증합니다.**

**macOS**

```bash
# ① 코드 발송 — 내 메일함에 6자리가 옴
curl -X POST http://localhost:8080/api/v1/auth/email/verify-request \
  -H "Content-Type: application/json" \
  -d '{"email":"me@example.com"}'

# ② 코드 확인
curl -X POST http://localhost:8080/api/v1/auth/email/verify \
  -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","code":"123456"}'

# ③ 가입
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"test1234","nickname":"테스트"}'
```

**Windows (PowerShell)**

```powershell
$h = @{ "Content-Type" = "application/json; charset=utf-8" }
$b = [System.Text.Encoding]::UTF8

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/email/verify-request `
  -Headers $h -Body $b.GetBytes('{"email":"me@example.com"}')

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/email/verify `
  -Headers $h -Body $b.GetBytes('{"email":"me@example.com","code":"123456"}')

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/signup `
  -Headers $h -Body $b.GetBytes('{"email":"me@example.com","password":"test1234","nickname":"테스트"}')
```

> PowerShell 은 **한글을 바이트로 바꿔 보냅니다.** 안 그러면 닉네임이 `???` 로 저장됩니다.
> 메일이 안 오면 **스팸함**을 봅니다. 개인 Gmail 발신이라 거기로 갈 수 있습니다.

---

**지름길 — Redis 에 인증 표시를 직접 넣습니다.**

```bash
docker compose exec redis redis-cli SET "emailverified:me@example.com" 1 EX 1800
```

이 키가 있으면 `signup` 이 *"인증을 마쳤다"* 고 봅니다. **30분 뒤 사라집니다.**

> 메일 설정이 안 됐거나 여러 계정을 빨리 만들 때 씁니다.
> `AUTH_MAIL_PASSWORD` 가 맞는지는 이 방법으로는 확인되지 않습니다.

---

**로그인하고 쿠키를 받습니다.**

```bash
curl -c cookies.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"me@example.com","password":"test1234"}'

curl -b cookies.txt http://localhost:8080/api/v1/auth/me
```

`-c` 가 쿠키를 파일에 저장하고 `-b` 가 그것을 실어 보냅니다.
**두 번째가 200 이면 로그인부터 인증까지 전부 이어진 것입니다.**

```json
{
  "code": "SUCCESS",
  "data": {
    "accountId": "01a0582d-d666-7b62-8541-129e42772053",
    "email": "me@example.com",
    "role": "USER",
    "authProvider": "LOCAL"
  }
}
```

---

**계정을 지우고 다시 하려면**

```bash
docker compose exec postgres psql -U auth_svc -d auth_db \
  -c "DELETE FROM refresh_token_log; DELETE FROM outbox; DELETE FROM account;"
docker compose exec redis redis-cli FLUSHDB
```

> `FLUSHDB` 는 **`emailverified:` 표시도 지웁니다.** 인증을 마친 뒤에 비우면
> 가입이 `EMAIL_NOT_VERIFIED` 로 막힙니다. 순서에 주의합니다.

<br><br>

---

## 2. 인증이 어떻게 도는가

**API 를 부르기 전에 이 장을 한 번 읽으면 3장이 훨씬 쉽습니다.**

<br><br>

---

### 2-1. 세션이 아니라 JWT 입니다

로그인 상태를 기억하는 방식이 두 가지 있습니다.

| | 세션 | JWT (우리 방식) |
|---|---|---|
| 서버가 기억하나 | 예. 서버 메모리나 DB 에 "누가 로그인했는지" 를 둠 | **아니오.** 토큰 안에 그 정보가 들어 있음 |
| 서버가 여러 대면 | 세션 저장소를 공유해야 함 | 공개키만 있으면 어느 서버든 검증 가능 |
| 로그아웃 | 서버가 세션을 지우면 끝 | **즉시 무효화가 안 됨.** 만료까지 살아 있음 |
| 우리 구조에서 | 서비스 14개가 세션을 공유해야 함 | **게이트웨이 하나가 검증하고 서비스들은 헤더만 믿음** |

**JWT** 는 `헤더.페이로드.서명` 세 덩이를 점으로 이은 문자열입니다.

```
eyJhbGciOiJSUzI1NiJ9 . eyJzdWIiOiIwMWEwNTgyZC0uLi4iLCJyb2xlIjoiVVNFUiIsInR5cCI6ImFjY2VzcyIsImV4cCI6MTc1NjcxMjAwMH0 . MEUCIQD...
     헤더                                  페이로드 (Base64 — 누구나 읽을 수 있음)                              서명
     {"alg":"RS256"}                       {"sub":"01a0...","role":"USER","typ":"access","exp":1756712000}        개인키로 만듦
```

> **페이로드는 암호화가 아닙니다.** 누구나 읽을 수 있고, 다만 **고치면 서명이 안 맞아
> 게이트웨이가 거부합니다.** 그래서 비밀번호 같은 것은 절대 담지 않습니다.

---

**토큰에 든 값입니다.**

| claim | 값 | 뜻 |
|---|---|---|
| `sub` | 계정 UUID | **이 값이 `X-User-Id` 가 됩니다** |
| `role` | `USER` · `ADMIN` | 이 값이 `X-User-Role` 이 됩니다 |
| `typ` | `access` · `refresh` | 두 토큰을 구분합니다 |
| `exp` | 만료 시각 | 게이트웨이가 검사합니다 |
| `iat` | 발급 시각 | 토큰 일괄 폐기의 기준입니다 |
| `jti` | 토큰 고유 ID | 리프레시 토큰의 Redis 키입니다 |
| `iss` | `pawtrail-auth` | 발급자 |

<br><br>

---

### 2-2. 토큰이 두 개인 이유

```
로그인 성공
    │
    ├──▶  access_token   30분     Path=/                 모든 요청에 실려 감
    │                            게이트웨이가 서명·만료·typ 을 보고 통과시킴
    │                            auth 는 이것을 저장하지 않음 (무상태)
    │
    └──▶  refresh_token  14일     Path=/api/v1/auth      갱신·로그아웃에만 실려 감
                                 Redis 에 refresh:{jti} 로 저장됨
                                 쓸 때마다 새것으로 교체됨 (로테이션)
```

---

**하나만 쓰면 한쪽이 무너집니다.**

| 토큰 하나를 | 문제 |
|---|---|
| 30분짜리만 | 30분마다 다시 로그인해야 합니다 |
| 14일짜리만 | 유출되면 14일 동안 누구나 그 계정입니다. 회수할 방법도 없습니다 |

**둘로 나누면** 자주 나가는 액세스 토큰은 짧게 두어 유출 피해를 줄이고,
리프레시 토큰은 갱신할 때만 나가게 해 노출을 줄입니다.

---

**`typ` claim 이 둘을 가릅니다.**

| 어디서 | 받는 것 | 다른 것이 오면 |
|---|---|---|
| 게이트웨이 | `access` 만 | 401 |
| `POST /refresh` · `/logout` | `refresh` 만 | 401 |

> 이것이 없으면 **리프레시 토큰을 `access_token` 쿠키에 넣어 14일간 쓸 수 있고,**
> 반대로 액세스 토큰을 `/refresh` 에 보내면 복제로 오인되어 **계정이 통째로 잠깁니다.**

<br><br>

---

### 2-3. 왜 쿠키인가

토큰을 브라우저 어디에 둘지가 두 가지입니다.

| | localStorage + Authorization 헤더 | HttpOnly 쿠키 (우리 방식) |
|---|---|---|
| 자바스크립트가 읽을 수 있나 | 예 | **아니오** |
| XSS (스크립트 주입) 에 | **토큰이 통째로 털림** | 안전 |
| CSRF (다른 사이트에서 요청 위조) 에 | 안전 | `SameSite=Strict` 로 막음 |
| 프론트가 할 일 | 매 요청에 헤더를 붙임 | **없음.** 브라우저가 자동으로 실음 |

**XSS 는 라이브러리 하나만 오염돼도 터지고, CSRF 는 쿠키 속성 하나로 막힙니다.**
그래서 쿠키를 골랐습니다.

---

**쿠키 속성입니다.**

```
Set-Cookie: access_token=...;  HttpOnly; SameSite=Strict; Path=/;            Max-Age=1800
Set-Cookie: refresh_token=...; HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600
```

| 속성 | 뜻 |
|---|---|
| `HttpOnly` | 자바스크립트가 못 읽습니다 |
| `SameSite=Strict` | 다른 사이트에서 시작된 요청에는 안 실립니다 |
| `Path=/api/v1/auth` | 리프레시 토큰은 **이 경로 아래로만** 나갑니다 |
| `Secure` | HTTPS 에서만 실립니다. **로컬(`local` 프로파일)에서는 빠집니다** |

> **프론트는 토큰을 볼 수 없으므로 로그인 여부를 `GET /auth/me` 로만 알 수 있습니다.**
> 200 이면 로그인, 401 이면 아닙니다.

<br><br>

---

### 2-4. auth 와 게이트웨이의 역할 분담

```
개인키 (환경변수)      공개키 (config 저장소)
 │                        │
 ▼                        ▼
auth  ──▶  토큰  ──▶  게이트웨이     서명 확인 → sub · role · typ 을 꺼냄
서명함                  검증만 함

개인키가 새면   누구나 유효한 토큰을 만들 수 있음  →  환경변수에만, 절대 커밋 안 함
공개키가 새면   아무 일도 없음                   →  config 저장소에 그대로 둠
```

**auth 가 하는 것과 안 하는 것입니다.**

| auth 가 함 | auth 가 안 함 |
|---|---|
| 토큰 발급 (로그인 · 갱신 · 소셜) | 매 요청의 토큰 검증 — 게이트웨이 |
| 리프레시 토큰 저장 · 폐기 | `X-User-Id` 헤더 붙이기 — 게이트웨이 |
| 계정 관리 (가입 · 비밀번호 · 탈퇴) | 프로필 · 닉네임 — user 서비스 |

> **auth 도 자기 API 에서는 헤더를 믿습니다.** `GET /me` 가 받는 `X-User-Id` 는
> 게이트웨이가 넣어 준 것이고, auth 는 자기가 만든 토큰이라도 직접 파싱하지 않습니다.
> 갱신·로그아웃만 예외로 리프레시 토큰을 직접 읽습니다.

<br><br>

---

### 2-5. 로그인부터 로그아웃까지

```
[ 로그인 ]
브라우저  ──▶  게이트웨이  ──▶  auth
                              POST /login  {email, password}
                                │
                                ├──▶  비밀번호 대조 (BCrypt)
                                ├──▶  토큰 2개 발급 · Redis 에 리프레시 저장 · 이력 1행
                                └──▶  Set-Cookie 2개로 응답
                                       (auth 는 여기서 손을 뗌)

[ 그 뒤 모든 요청 ]
브라우저  ──▶  게이트웨이  ──▶  place · pet · user ...
   쿠키 자동 첨부     │
                    ├──▶  access_token 쿠키의 JWT 를 공개키로 검증
                    ├──▶  typ 이 access 인지 · 만료 안 됐는지
                    └──▶  X-User-Id · X-User-Role 헤더를 붙여 넘김
                           auth 는 관여하지 않음

[ 30분 뒤 ]
브라우저  ──▶  게이트웨이  ──▶  place ...
                    └──▶  401 (액세스 토큰 만료)
   │
   └──▶  프론트 인터셉터가 자동으로  POST /api/v1/auth/refresh
                                    │
                                    ├──▶  refresh_token 쿠키의 jti 로 Redis 조회
                                    ├──▶  새 토큰 2개 발급 · 옛 리프레시 폐기
                                    └──▶  Set-Cookie 2개
   │
   └──▶  원래 요청을 다시 보냄  →  200

[ 로그아웃 ]
브라우저  ──▶  게이트웨이  ──▶  auth   POST /logout
                                └──▶  Redis 에서 refresh:{jti} 삭제 · 쿠키 2개 만료
                                       액세스 토큰은 최대 30분 더 살아 있음 (JWT 라 회수 불가)
```

---

**프론트가 맞춰야 하는 것입니다.**

| | 왜 |
|---|---|
| 401 을 받으면 `/refresh` 를 부르고 원래 요청을 재시도하는 인터셉터 | 30분마다 사용자가 로그인하지 않게 |
| 동시에 여러 요청이 401 을 받았을 때 `/refresh` 를 한 번만 부르는 큐잉 | 여러 번 부르면 뒤엣것이 옛 토큰이라 거부됨 |
| 로그인 여부를 `GET /auth/me` 로 판단 | 쿠키를 읽을 수 없으므로 |
| 닉네임은 `GET /users/me` 로 따로 | auth 응답에 없음 |

<br><br>

---

## 3. API 17개

전부 게이트웨이(`:8080`)를 거쳐 부릅니다. 응답은 공통 형식입니다.

```json
{ "code": "SUCCESS", "message": "...", "data": { }, "traceId": "..." }
```

<br><br>

---

### 3-1. 목록

| # | 메서드 | 경로 | 인증 | 하는 일 |
|---|---|---|---|---|
| 1 | POST | `/api/v1/auth/email/verify-request` | 불필요 | 가입용 인증 코드 발송 |
| 2 | POST | `/api/v1/auth/email/verify` | 불필요 | 코드 확인 |
| 3 | POST | `/api/v1/auth/signup` | 불필요 | 회원가입 |
| 4 | POST | `/api/v1/auth/login` | 불필요 | 로그인 |
| 5 | POST | `/api/v1/auth/refresh` | 리프레시 쿠키 | 토큰 갱신 |
| 6 | POST | `/api/v1/auth/logout` | 리프레시 쿠키 | 로그아웃 |
| 7 | GET | `/api/v1/auth/me` | **필요** | 내 계정 |
| 8 | PATCH | `/api/v1/auth/me/password` | **필요** | 비밀번호 변경 |
| 9 | POST | `/api/v1/auth/password/reset-request` | 불필요 | 재설정 코드 발송 |
| 10 | POST | `/api/v1/auth/password/reset` | 불필요 | 비밀번호 재설정 |
| 11 | GET | `/api/v1/auth/oauth/{provider}/authorize` | 불필요 | 구글로 보냄 (302) |
| 12 | GET | `/api/v1/auth/oauth/{provider}/callback` | 불필요 | 구글에서 돌아옴 (302) |
| 13 | POST | `/api/v1/auth/withdraw/verify-request` | **필요** | 탈퇴용 인증 코드 발송 |
| 14 | DELETE | `/api/v1/auth/me` | **필요** | 탈퇴 |
| 15 | GET | `/api/v1/admin/accounts/outbox` | **ADMIN** | 포기한 이벤트 목록 |
| 16 | POST | `/api/v1/admin/accounts/outbox/{id}/retry` | **ADMIN** | 재발행 |

> 15개가 아니라 16개인 이유 — `{provider}` 자리에 지금은 `google` 만 옵니다.
> 표에서는 하나로 세었습니다.

---

**"인증 불필요" 는 두 곳에 같은 목록이 있습니다.**

| 어디 | 무엇 |
|---|---|
| config `gateway-server.yml` 의 `app.gateway.permit-all` | 게이트웨이가 토큰 없이 통과시킴 |
| config `auth-service.yml` 의 `app.auth.permit-all` | auth 의 보안 체인이 열어 둠 |

```
/api/v1/auth/signup              /api/v1/auth/login
/api/v1/auth/refresh             /api/v1/auth/logout
/api/v1/auth/oauth/**
/api/v1/auth/password/reset-request    /api/v1/auth/password/reset
/api/v1/auth/email/verify-request      /api/v1/auth/email/verify
```

> **9줄이 양쪽에 똑같이 있어야 합니다.** 한쪽에만 빠지면 그 경로가 401 이 됩니다.
> 게이트웨이에만 빠지면 게이트웨이 로그에 `토큰 쿠키가 없습니다`, auth 에만 빠지면
> auth 로그에 `인증 실패 (401)` 이 남아 어느 쪽인지 알 수 있습니다.
>
> `withdraw/verify-request` 는 **로그인한 사람만 부르므로 여기 없습니다.**

<br><br>

---

### 3-2. 회원가입 — 이메일 인증을 먼저

```
① POST /email/verify-request  {email}
        │
        ├──▶  발송 제한 검사       60초 쿨다운 · 시간당 5통
        ├──▶  6자리 코드 생성      Redis  emailverify:{email}  TTL 10분
        └──▶  메일 발송            Gmail SMTP
        │
        ▼
② POST /email/verify  {email, code}
        │
        ├──▶  코드 대조            틀리면 시도 횟수 +1, 5회면 코드 폐기
        └──▶  통과 표시 저장       Redis  emailverified:{email}  TTL 30분
        │
        ▼
③ POST /signup  {email, password, nickname}
        │
        ├──▶  emailverified 가 있나          없으면 400 EMAIL_NOT_VERIFIED
        ├──▶  이미 가입된 이메일인가           있으면 409 EMAIL_ALREADY_EXISTS
        ├──▶  account 행 INSERT              BCrypt 해시, status ACTIVE
        ├──▶  outbox 에 account.created      {accountId, email, nickname}
        └──▶  커밋 뒤 emailverified 삭제      같은 인증으로 두 번 가입 방지
        │
        ▼
④ 201 Created  {accountId, email, role, authProvider}
        │
        └──▶  카프카  account.created  ──▶  user 서비스가 프로필을 만듦
                                            (nickname 은 여기서 저장됨)
```

---

**왜 이메일 인증이 먼저인가**

`email` 을 계정의 복구 수단(비밀번호 재설정)으로 삼기로 했는데, **인증 없이 가입되면
그 복구 수단이 남의 것일 수 있습니다.** 아무나 남의 이메일로 계정을 만들어 두면
진짜 주인은 나중에 *"이미 가입됨"* 으로 막힙니다.

---

**요청과 응답입니다.**

**① 코드 발송**

```http
POST /api/v1/auth/email/verify-request
{ "email": "me@example.com" }
```

| 응답 | 언제 |
|---|---|
| 200 | 발송됨 |
| 409 `EMAIL_ALREADY_EXISTS` | 이미 가입된 이메일. **여기서는 알려 줍니다** — 안 알리면 가입이 왜 안 되는지 모릅니다 |
| 429 `MAIL_SEND_COOLDOWN` | 60초 안에 다시 요청함, 또는 시간당 5통 초과 |
| 500 `MAIL_SEND_FAILED` | SMTP 실패. 사용자가 안 오는 메일을 기다리는 것보다 낫습니다 |

**② 코드 확인**

```http
POST /api/v1/auth/email/verify
{ "email": "me@example.com", "code": "482913" }
```

| 응답 | 언제 |
|---|---|
| 200 | 통과. 30분 안에 가입해야 합니다 |
| 400 `INVALID_VERIFICATION_CODE` | 틀림 (남은 시도 횟수가 줄어듦) 또는 만료 |
| 429 `TOO_MANY_VERIFICATION_ATTEMPTS` | 5회 틀림. 코드가 폐기됐으니 다시 받아야 합니다 |

**③ 가입**

```http
POST /api/v1/auth/signup
{ "email": "me@example.com", "password": "test1234", "nickname": "테스트" }
```

| 항목 | 규칙 |
|---|---|
| `email` | 형식 검사 |
| `password` | **8자 이상 72자 이하, 72바이트 이하.** 문자 조합은 강제하지 않습니다 |
| `nickname` | 30자 이하. **auth 는 저장하지 않고 이벤트로 user 에 넘깁니다** |

```json
201 Created
{ "data": { "accountId": "01a0...", "email": "me@example.com", "role": "USER", "authProvider": "LOCAL" } }
```

> **가입 후 자동 로그인은 없습니다.** 로그인 화면으로 보냅니다.
> 비밀번호 상한이 72바이트인 것은 BCrypt 제약입니다. 한글은 한 글자가 3바이트라
> `@MaxBytes(72)` 검증을 따로 둡니다.

<br><br>

---

### 3-3. 로그인 · 갱신 · 로그아웃

**로그인**

```
POST /login  {email, password}
        │
        ├──▶  이메일로 계정 조회
        │       없음                   ──▶  401 LOGIN_FAILED
        │       탈퇴함                 ──▶  403 ACCOUNT_WITHDRAWN
        │       소셜 계정 (비밀번호 없음)  ──▶  401 LOGIN_FAILED   ← 소셜인지 안 알려 줌
        │
        ├──▶  비밀번호 대조            BCrypt.matches
        │       틀림                   ──▶  401 LOGIN_FAILED   ← 이메일 없음과 같은 코드
        │
        ├──▶  토큰 2개 발급            access (typ=access, 30분) · refresh (typ=refresh, 14일)
        ├──▶  Redis                   refresh:{jti} = accountId   TTL 14일   (커밋 뒤에)
        ├──▶  refresh_token_log       1행  login_id 새로 · token_id = jti
        ├──▶  account.last_login_at   갱신
        │
        ▼
200 OK  +  Set-Cookie 2개  +  {accountId, email, role, authProvider}
```

```http
POST /api/v1/auth/login
{ "email": "me@example.com", "password": "test1234" }
```

> **이메일이 없는 것과 비밀번호가 틀린 것을 같은 코드로 냅니다.** 나누면 응답만 보고
> *"이 이메일은 가입돼 있다"* 를 알아낼 수 있습니다. 소셜 계정도 같은 이유로 숨깁니다.
> **탈퇴한 계정만 알려 줍니다** — 안 알리면 비밀번호를 계속 다시 입력하게 됩니다.

---

**갱신**

```
POST /refresh   (refresh_token 쿠키만, 바디 없음)
        │
        ├──▶  토큰을 읽음              서명 · 만료 · typ=refresh
        ├──▶  계정 확인               탈퇴했거나 iat < tokens_valid_from 이면 401
        │
        └──▶  Redis Lua 스크립트 (한 덩어리로)
                GETDEL refresh:{옛jti}           옛 토큰을 소비
                │
                ├── 있음 ──▶  SET refresh:{새jti}              새 토큰 활성화
                │             SET refreshgrace:{옛jti} 새토큰    30초 유예 항목
                │             │
                │             └──▶  옛 이력 행 revoked_at · 새 행 INSERT (login_id 물려받음)
                │                   200 + Set-Cookie 2개
                │
                └── 없음 ──▶  refreshgrace:{옛jti} 가 있나
                                │
                                ├── 있음 ──▶  30초 안의 경합 (탭 두 개)   →  그 토큰을 그대로 돌려줌  200
                                │
                                └── 없음 ──▶  유예 밖의 재사용 = 복제       →  tokens_valid_from 을 지금으로
                                                                              그 계정 토큰 전부 폐기  401
```

```http
POST /api/v1/auth/refresh
(바디 없음)
```

| 응답 | 언제 |
|---|---|
| 200 + Set-Cookie 2개, `data: null` | 갱신됨 |
| 401 `INVALID_REFRESH_TOKEN` | 쿠키 없음 · 만료 · 서명 오류 · 복제 탐지 · 폐기됨 — **전부 한 코드** |

> `data` 가 비어 있는 것은 **프론트가 이것을 부르는 자리가 401 인터셉터 안**이라
> 새 쿠키만 필요하기 때문입니다.
> 401 이 한 코드인 것은 **어느 경우든 프론트가 할 일이 로그인 화면으로 보내는 것 하나**라서입니다.

---

**로테이션과 유예 30초가 왜 있나**

| 상황 | 처리 |
|---|---|
| 정상 갱신 | 옛 토큰을 소비하고 새 토큰을 줌. **옛 토큰은 못 씀** |
| 탭 두 개가 동시에 갱신 | 한쪽이 이기고, 진 쪽은 30초 안이면 **이긴 쪽 토큰을 그대로 받음** |
| 30초 지난 옛 토큰이 또 들어옴 | **복제로 판단.** 누가 토큰을 훔쳐 쓴 것이므로 그 계정 토큰을 전부 폐기 |

> 유예가 없으면 탭 두 개만 열어도 한쪽이 강제 로그아웃됩니다.
> 유예가 무한이면 훔친 토큰을 영원히 씁니다. **30초는 업계 기본값(Okta · Cognito)입니다.**

---

**로그아웃**

```http
POST /api/v1/auth/logout
(바디 없음)
```

```
Redis 에서 refresh:{jti} 삭제  →  refresh_token_log 에 revoked_at  →  쿠키 2개 만료  →  200
```

> **토큰이 없거나 만료됐어도 200 입니다.** 401 이면 클라이언트가 지우지 못하는 쿠키를
> 들고 갇힙니다. 로그아웃은 여러 번 불러도 같아야 합니다.
>
> **액세스 토큰은 최대 30분 더 살아 있습니다.** JWT 라 회수할 수 없고, 그 대신
> 수명을 짧게 둔 것입니다.

<br><br>

---

### 3-4. 내 계정 · 비밀번호 변경

**둘 다 로그인이 필요합니다.** 게이트웨이가 넣어 준 `X-User-Id` 로 계정을 찾습니다.

**내 계정**

```http
GET /api/v1/auth/me
```

```json
{ "data": { "accountId": "01a0...", "email": "me@example.com", "role": "USER", "authProvider": "LOCAL" } }
```

> **`nickname` 이 없습니다.** `user_profile` 의 데이터라 auth 가 user 를 불러야 하는데,
> auth 는 다른 서비스를 부르지 않기로 했습니다. 프론트는 `GET /users/me` 를 따로 부릅니다.

---

**비밀번호 변경**

```http
PATCH /api/v1/auth/me/password
{ "currentPassword": "test1234", "newPassword": "newpass5678" }
```

```
소셜 계정인가              ──▶  400 PASSWORD_NOT_SUPPORTED   (비밀번호가 없음)
현재 비밀번호가 맞나         ──▶  400 CURRENT_PASSWORD_MISMATCH
새 비밀번호로 교체
tokens_valid_from 갱신     ──▶  이 계정의 모든 리프레시 토큰이 무효
쿠키 2개 만료              ──▶  200. 다시 로그인해야 함
```

> **바꾸면 본인도 로그아웃됩니다.** 다른 기기의 세션도 함께 끊깁니다.
> 안 그러면 *"비밀번호를 바꿨는데 30분 뒤 갑자기 튕기는"* 상태가 됩니다.
>
> **현재 비밀번호에는 길이 규칙이 없습니다.** 규칙이 바뀌기 전에 만든 비밀번호가
> 형식에 안 맞아 못 바꾸는 일이 없어야 합니다.

<br><br>

---

### 3-5. 비밀번호 재설정 — 잊었을 때

```
① POST /password/reset-request  {email}
        │
        ├──▶  계정이 있고 비밀번호를 쓰는 계정이면   코드 발송  (pwreset:{email})
        ├──▶  계정이 없거나 소셜이면              아무것도 안 함
        └──▶  어느 쪽이든 200                    ← 계정 존재를 숨김
        │
        ▼
② POST /password/reset  {email, code, newPassword}
        │
        ├──▶  코드 대조                5회 틀리면 폐기
        ├──▶  비밀번호 교체            BCrypt
        ├──▶  tokens_valid_from 갱신   그 계정의 모든 리프레시 토큰이 무효가 됨
        └──▶  200
```

```http
POST /api/v1/auth/password/reset-request
{ "email": "me@example.com" }

POST /api/v1/auth/password/reset
{ "email": "me@example.com", "code": "482913", "newPassword": "newpass5678" }
```

---

**반드시 지키는 것 셋입니다.**

| 규칙 | 왜 |
|---|---|
| **계정이 없어도 200** | 아니면 응답만 보고 회원 이메일 목록을 캐낼 수 있습니다 |
| **시도 5회 제한** | 6자리는 백만 가지뿐이라 무차별 대입이 실제로 됩니다 |
| **소셜 계정은 대상이 아님** | 비밀번호가 없습니다. 판단은 `authProvider.hasPassword()` 로 |

> 발송 실패도 **200 으로 삼키고 로그만 남깁니다.** 500 을 내면 그 자체가
> *"이 이메일은 가입돼 있다"* 는 신호가 됩니다. 발송 제한에 걸려도 같습니다.
> [3-2](#3-2-회원가입--이메일-인증을-먼저) 의 가입 인증과 **정반대**인 점입니다.

---

**"변경" 과 "재설정" 을 합치면 안 됩니다.**

| | 변경 | 재설정 |
|---|---|---|
| 상태 | 로그인됨 | 비밀번호를 모름 |
| 본인 확인 | 현재 비밀번호 | 메일 코드 |
| 합치면 | **이메일만 알면 남의 비밀번호를 바꿀 수 있음** | |

<br><br>

---

### 3-6. 소셜 로그인 — 구글

```
① GET /oauth/google/authorize
        │
        ├──▶  state · nonce 생성       Redis  oauth:state:{state} = nonce   TTL 5분
        ├──▶  oauth_state 쿠키         HttpOnly · SameSite=Lax · Path=/api/v1/auth/oauth
        └──▶  302  →  구글 로그인 화면
                      │
                      ▼
              사용자가 구글에서 로그인하고 동의
                      │
                      ▼
② GET /oauth/google/callback?code=...&state=...     ← 구글이 브라우저를 여기로 보냄
        │
        ├──▶  state 대조             쿠키 값 = 쿼리 값 = Redis 에 있는 값   (1회용 소비)
        ├──▶  code 로 토큰 교환       POST oauth2.googleapis.com/token  (client-secret 사용)
        ├──▶  id_token 검증          구글 JWKS 로 서명 · nonce · email_verified
        │
        ├──▶  계정 매칭
        │       provider_user_id = 구글 sub 인 계정   ──▶  재로그인          isNew=false
        │       없고, email 이 같은 LOCAL 계정         ──▶  자동 연결          isNew=false
        │                                                 (auth_provider 는 LOCAL 유지)
        │       둘 다 없음                            ──▶  신규 GOOGLE 계정   isNew=true
        │                                                 account.created (nickname=null)
        │       탈퇴한 계정                            ──▶  /login/error?reason=WITHDRAWN
        │
        ├──▶  토큰 2개 발급 · Set-Cookie
        └──▶  302  →  {frontend}/login/success?isNew=true|false

실패하면  302  →  {frontend}/login/error?reason=FAILED     (JSON 을 낼 수 없는 자리)
취소하면  302  →  {frontend}/login                          (reason 없음)
```

---

**JSON API 가 아닙니다.** 둘 다 브라우저를 다른 곳으로 보내는 302 응답입니다.

| | 요청 | 응답 |
|---|---|---|
| `authorize` | 프론트가 `window.location = ...` 로 이동 | 302 → 구글 |
| `callback` | 구글이 브라우저를 보냄 | 302 → 프론트 `/login/success` 또는 `/login/error` |

> 실패해도 JSON 을 낼 수 없습니다. 콜백은 **브라우저 주소창이 오는 곳**이라 JSON 을
> 내면 흰 화면에 날 JSON 이 보입니다. 그래서 `/login/error?reason=` 으로 보냅니다.

---

**프론트 라우트 셋입니다.**

| 경로 | 언제 |
|---|---|
| `/login/success?isNew=true` | 신규 가입. **닉네임이 없으므로 프로필 설정으로 유도** |
| `/login/success?isNew=false` | 재로그인 또는 기존 계정에 연결됨 |
| `/login/error?reason=WITHDRAWN` | 탈퇴한 계정 |
| `/login/error?reason=FAILED` | 그 밖의 전부. 사용자가 할 일은 "다시 시도" 하나라 나누지 않음 |
| `/login` (쿼리 없음) | 구글 화면에서 취소함 |

---

**계정 매칭이 셋으로 갈립니다.**

| 조건 | 결과 |
|---|---|
| `provider_user_id` 가 구글 `sub` 와 같은 계정이 있음 | 그 계정으로 로그인 |
| 없고, 같은 이메일의 **LOCAL** 계정이 있음 | **그 계정에 `sub` 를 연결하고 로그인.** `auth_provider` 는 LOCAL 유지 |
| 둘 다 없음 | GOOGLE 계정을 새로 만듦. `account.created` 발행 (`nickname: null`) |

> **자동 연결이 안전한 이유** — LOCAL 가입도 이메일 인증을 거쳤고 구글도 이메일을 확인해 줬으므로
> 양쪽 다 **그 이메일의 주인임이 확인된 상태**입니다.
>
> **`auth_provider` 를 LOCAL 로 두는 이유** — GOOGLE 로 바꾸면 `hasPassword()` 가 false 가 되어
> **비밀번호가 멀쩡히 있는데 비밀번호 로그인이 막힙니다.**

---

**저장하는 것은 구글 `sub` 하나뿐입니다.**

| 값 | 저장 | 왜 |
|---|---|---|
| `code` | ✗ | 1회용, 수분 만료 |
| 구글 `access_token` | ✗ | 구글 API 를 대신 호출할 때만 필요한데 우리는 안 씀 |
| `id_token` 의 `sub` | ✓ `provider_user_id` | 로그인할 때마다 같은 값이 와서 계정을 찾는 열쇠 |

로그인 뒤에는 **우리 JWT** 를 쓰므로 구글 토큰은 신원 확인 순간까지만 살아 있습니다.

---

**`state` · `nonce` · 상태 쿠키가 막는 것입니다.**

| 장치 | 막는 것 |
|---|---|
| `state` (Redis, 5분, 1회용) | 우리가 시작하지 않은 콜백 |
| `oauth_state` 쿠키 | **다른 브라우저에서 시작한 콜백.** 공격자가 자기 콜백 URL 을 피해자에게 열게 하는 것 |
| `nonce` (`id_token` 안) | 가로챈 `id_token` 의 재사용 |

> `oauth_state` 쿠키만 **`SameSite=Lax`** 입니다. 콜백은 구글에서 오는 요청이라
> `Strict` 면 쿠키가 안 실립니다. 토큰 쿠키는 그대로 `Strict` 입니다.

<br><br>

---

### 3-7. 탈퇴 — 메일 인증을 거침

```
① POST /withdraw/verify-request     (바디 없음 — 로그인한 계정의 이메일로 보냄)
        │
        └──▶  코드 발송       Redis  withdraw:{email}  TTL 10분   발송 제한은 용도별로 따로
        │
        ▼
② DELETE /me  {code}
        │
        ├──▶  코드 대조 (Lua)        맞으면 지우고 진행 · 틀리면 코드는 남기고 시도 +1
        │
        ├──▶  account 행 갱신        status  WITHDRAWN
        │                            email   withdrawn+{id}@pawtrail.invalid   ← 재가입 가능하게
        │                            provider_user_id  NULL
        ├──▶  토큰 전부 폐기          tokens_valid_from 갱신 · 이력 전부 revoked_at
        ├──▶  outbox                 account.withdrawn  {accountId}
        └──▶  200 + 쿠키 2개 만료
        │
        └──▶  카프카  ──▶  user · pet · report · review · notification 이 각자 데이터를 지움
```

```http
POST /api/v1/auth/withdraw/verify-request
(바디 없음)

DELETE /api/v1/auth/me
{ "code": "482913" }
```

---

**왜 메일 인증인가**

비밀번호로 확인하면 **소셜 계정은 비밀번호가 없어 확인할 수 없습니다.**
이메일은 로컬이든 소셜이든 항상 있습니다. 게다가 비밀번호는 브라우저 자동완성으로
뚫릴 수 있지만 **메일함은 별도 로그인이 필요해 오히려 더 강한 확인**입니다.

---

**행을 지우지 않고 식별자를 끊습니다.**

| 컬럼 | 탈퇴 후 |
|---|---|
| `status` | `WITHDRAWN` |
| `email` | `withdrawn+{accountId}@pawtrail.invalid` |
| `provider_user_id` | `NULL` |
| 행 자체 | **남김** |

| 왜 남기나 | 왜 끊나 |
|---|---|
| 이벤트 소비가 실패했을 때 *"이 accountId 가 정말 탈퇴했나"* 를 확인할 근거 | 안 끊으면 **그 이메일·그 구글 계정으로 영영 재가입이 안 됨** |
| `refresh_token_log` 가 고아가 되지 않음 | `.invalid` 는 존재할 수 없는 TLD 라 실수로 메일이 나가도 안 감 |

> **재가입하면 완전히 새 계정입니다.** 옛 즐겨찾기·후기·반려동물은 안 돌아옵니다.
> 탈퇴가 곧 삭제이므로 그것이 맞는 동작이며, **탈퇴 확인 화면에 그 문구가 있어야 합니다.**

<br><br>

---

### 3-8. 관리자 — outbox 재발행

이벤트 발행이 10번 실패하면 `OutboxRelay` 가 포기합니다. **그 뒤로는 아무도 다시
보내지 않으므로** 사람이 다시 보내는 수단입니다.

```http
GET /api/v1/admin/accounts/outbox?page=0&size=20
```

```json
{
  "data": {
    "content": [
      {
        "id": "7f3e...",
        "eventId": "a1b2...",
        "topic": "account.created",
        "aggregateType": "Account",
        "aggregateId": "01a0...",
        "createdAt": "2026-09-01T14:22:10",
        "retryCount": 10,
        "lastError": "TimeoutException: Topic account.created not present in metadata"
      }
    ],
    "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
  }
}
```

| 응답에 있음 | 없음 |
|---|---|
| `retryCount` · `lastError` — **눌러도 되는 상황인지** 판단하는 재료 | `payload` — 이메일·닉네임이 들어 있어 안 담습니다 |

```
TimeoutException      눌러도 됨 — 카프카가 잠깐 죽었던 것
직렬화 오류            눌러도 또 실패함 — 코드를 먼저 고쳐야 함
```

```http
POST /api/v1/admin/accounts/outbox/{id}/retry
```

| 응답 | 언제 |
|---|---|
| 200 | 발행됨. `retryCount` 는 **그대로 둡니다** — "몇 번 실패한 뒤 사람이 보냈는지" 의 기록 |
| 500 `OUTBOX_REPUBLISH_FAILED` | 또 실패. 성공으로 응답하면 안 됩니다 |

> **비어 있는 목록이 정상입니다.** 재시도 상한을 넘긴 건만 뜨므로 뜨면 누르면 됩니다.
> 관리자 역할을 얻는 법과 부르는 법은 [7-1](#7-1-관리자-지정) · [7-2](#7-2-관리자-api-부르기) 에 있습니다.

<br><br>

---

### 3-9. 에러 코드 16개

| 코드 | HTTP | 언제 |
|---|---|---|
| `EMAIL_ALREADY_EXISTS` | 409 | 가입 인증 요청 · 가입 시 이미 있는 이메일 |
| `EMAIL_NOT_VERIFIED` | 400 | 인증 없이 가입 시도 |
| `INVALID_VERIFICATION_CODE` | 400 | 코드 틀림 · 만료 · 없음 (가입 · 재설정 · 탈퇴 공통) |
| `TOO_MANY_VERIFICATION_ATTEMPTS` | 429 | 5회 틀려 코드가 폐기됨 |
| `MAIL_SEND_COOLDOWN` | 429 | 60초 안 재요청 · 시간당 5통 초과 (가입 인증만) |
| `MAIL_SEND_FAILED` | 500 | SMTP 실패 (가입 인증 · 탈퇴 인증) |
| `LOGIN_FAILED` | 401 | 이메일 없음 · 비밀번호 틀림 · 소셜 계정 — **구분 안 함** |
| `ACCOUNT_WITHDRAWN` | 403 | 탈퇴한 계정으로 로그인 · 갱신 · 탈퇴 인증 |
| `PASSWORD_NOT_SUPPORTED` | 400 | 소셜 계정이 비밀번호 변경 시도 |
| `INVALID_REFRESH_TOKEN` | 401 | 갱신 실패 전부 — **구분 안 함** |
| `CURRENT_PASSWORD_MISMATCH` | 400 | 비밀번호 변경 시 현재 비밀번호 틀림 |
| `UNSUPPORTED_OAUTH_PROVIDER` | 400 | `/oauth/naver/...` 처럼 없는 제공자 |
| `OAUTH_AUTHENTICATION_FAILED` | 401 | 구글 인증 실패 (콜백에서는 302 로 바뀜) |
| `INVALID_OAUTH_STATE` | 400 | state 불일치 (콜백에서는 302 로 바뀜) |
| `OUTBOX_REPUBLISH_FAILED` | 500 | 관리자 재발행 실패 |
| `ACCOUNT_NOT_FOUND` | 404 | 헤더의 계정 ID 로 못 찾음 (탈퇴 직후 등) |

---

**코드를 나누는 기준은 "프론트가 할 일이 다른가" 입니다.**

| 같은 코드 | 이유 |
|---|---|
| 이메일 없음 · 비밀번호 틀림 → `LOGIN_FAILED` | 나누면 이메일 존재가 드러남. 사용자가 할 일도 같음 |
| 갱신 실패 전부 → `INVALID_REFRESH_TOKEN` | 어느 경우든 로그인 화면으로 |

| 다른 코드 | 이유 |
|---|---|
| 코드 틀림 · 5회 초과 | 다시 입력 / 다시 받기 — **할 일이 다름** |
| 로그인 실패 · 탈퇴 계정 | 탈퇴는 알려야 비밀번호를 계속 다시 넣지 않음 |

공통 코드(`VALIDATION_FAILED` · `AUTHENTICATION_FAILED` · `ACCESS_DENIED` 등)는
공통 모듈 것이며 `service-template` README 7-4 에 있습니다.

<br><br>

---

## 4. 데이터

**세 곳에 나뉘어 있습니다.** 무엇을 어디에 두는지가 이 서비스 설계의 핵심입니다.

| 어디 | 무엇 | 왜 거기 |
|---|---|---|
| PostgreSQL `auth_db` | 계정 · 로그인 이력 · 발행할 이벤트 | 영구 보관, 트랜잭션 |
| Redis | 리프레시 토큰 · 인증 코드 · OAuth state | **TTL 로 저절로 사라지는 값.** 즉시 무효화가 필요한 값 |
| 카프카 | `account.created` · `account.withdrawn` | 다른 서비스에 알림 |

<br><br>

---

### 4-1. 테이블 — auth_db

```
auth_db
├── account              계정 1행 = 사람 1명
├── refresh_token_log    로그인 · 갱신 · 폐기 이력 (쌓기만 함)
└── outbox               발행 대기 이벤트 (공통 모듈, V1)
```

> `processed_event`(inbox) 가 **없습니다.** auth 는 이벤트를 받지 않습니다.

---

**`account`**

| 컬럼 | 타입 | 뜻 |
|---|---|---|
| `id` | uuid PK | **이 값이 `X-User-Id` 로 흐릅니다.** 다른 서비스가 전부 이것으로 사람을 가리킵니다 |
| `email` | varchar(255) NOT NULL UNIQUE | 로그인 ID 이자 복구 수단. 탈퇴하면 `withdrawn+{id}@pawtrail.invalid` 로 치환 |
| `password_hash` | varchar(60) | BCrypt. **소셜 계정은 NULL** |
| `auth_provider` | varchar(12) | `LOCAL` · `GOOGLE`. 이메일 가입 뒤 구글을 연결해도 LOCAL 유지 |
| `provider_user_id` | varchar(255) UNIQUE | 구글 `sub`. LOCAL 전용 계정은 NULL. 탈퇴하면 NULL |
| `role` | varchar(12) | `USER` · `ADMIN`. 관리자 지정은 [7-1](#7-1-관리자-지정) |
| `status` | varchar(12) | `ACTIVE` · `WITHDRAWN`. **`SUSPENDED` 는 없습니다** — 정지가 필요한 상황이 없음 |
| `tokens_valid_from` | timestamp NOT NULL | **이 시각 이후 발급된 토큰만 유효.** 비밀번호 변경 · 복제 탐지 · 탈퇴 때 올림 |
| `last_login_at` | timestamp | 마지막 로그인 |
| `created_at` … `deleted_by` | | `BaseEntity` 6컬럼. **`deleted_at` 은 쓰지 않습니다** — 탈퇴는 `status` 하나로만 |

> **`nickname` 이 없습니다.** `user_profile` 이 소유자이고 auth 는 가입 때 받아
> 이벤트로 넘기기만 합니다.

---

**`refresh_token_log`**

| 컬럼 | 타입 | 뜻 |
|---|---|---|
| `id` | uuid PK | |
| `account_id` | uuid INDEX | 누구 것인지. FK 는 없음 |
| `login_id` | uuid NOT NULL | **로그인 한 번을 묶는 값.** 갱신해도 옛 행에서 물려받음 |
| `token_id` | varchar(36) UNIQUE | JWT 의 `jti`. Redis 키와 같은 값 |
| `issued_at` · `expires_at` | timestamp | 발급 · 만료 |
| `revoked_at` | timestamp | 로그아웃 · 갱신(옛 것) · 일괄 폐기 때 채움 |
| `ip_address` | varchar(45) | **지금은 NULL.** nginx 를 붙일 때 채움 |
| `user_agent` | varchar(255) | 브라우저 |

**한 번의 로그인이 이렇게 쌓입니다.**

```
로그인      INSERT   login_id=L1  token_id=X
30분 뒤 갱신  UPDATE   X 행에 revoked_at
           INSERT   login_id=L1  token_id=Y     ← 같은 L1
또 갱신     UPDATE   Y 행에 revoked_at
           INSERT   login_id=L1  token_id=Z
로그아웃     UPDATE   Z 행에 revoked_at
```

> `login_id` 가 없으면 **마지막 행의 `issued_at` 은 로그인 시각이 아니라 마지막 갱신 시각**이라
> "언제 로그인했나" 를 알 수 없습니다. 기기가 둘이면 어느 행이 어느 로그인인지도 못 가릅니다.
>
> **`BaseEntity` 를 상속하지 않습니다.** `created_at` 이 `issued_at` 과 같고 `deleted_at` 이
> `revoked_at` 과 같아 절반이 중복됩니다. 시스템이 쌓는 로그라 `outbox` 와 같은 부류입니다.

---

**실제 토큰은 여기 없습니다.**

```
refresh_token_log   이력만.   "언제 발급됐고 언제 죽었나"
Redis refresh:{jti} 실물.     "지금 유효한가"
```

**즉시 무효화 때문입니다.** 로그아웃하면 Redis 키를 지우는 것으로 끝나고, DB 에는 기록만 남습니다.

<br><br>

---

### 4-2. Redis 키 9종

```
Redis (auth 가 쓰는 것)
│
├── 토큰
│   ├── refresh:{jti}                  accountId        14일    유효한 리프레시 토큰
│   └── refreshgrace:{옛jti}           새 토큰 문자열    30초    로테이션 유예
│
├── 인증 코드 (6자리, 10분)
│   ├── emailverify:{email}            코드             10분    가입 인증
│   ├── emailverified:{email}          1                30분    가입 인증 통과 표시
│   ├── pwreset:{email}                코드             10분    비밀번호 재설정
│   └── withdraw:{email}               코드             10분    탈퇴 인증
│       (각각 :attempt 키로 시도 횟수를 셈)
│
├── 발송 제한
│   ├── mailcooldown:{용도}:{email}     1                60초    한 통 보내면 1분 대기
│   └── mailhourly:{용도}:{email}       횟수             1시간   시간당 5통
│       용도 = signup · pwreset · withdraw
│
└── OAuth
    └── oauth:state:{state}            nonce            5분     콜백 대조용, 1회용
```

---

**왜 DB 가 아니라 Redis 인가**

| 값 | 이유 |
|---|---|
| 인증 코드 | 10분 뒤 사라져야 하는데 DB 면 지우는 배치가 필요합니다 |
| 리프레시 토큰 | 로그아웃 = 키 삭제. DB 면 매 갱신마다 "폐기됐나" 를 조회해야 합니다 |
| OAuth state | 5분짜리 임시 값. 테이블을 만들 이유가 없습니다 |

---

**직접 볼 때입니다.**

```bash
docker compose exec redis redis-cli KEYS 'refresh:*'
docker compose exec redis redis-cli GET  'emailverified:me@example.com'
docker compose exec redis redis-cli TTL  'pwreset:me@example.com'       # 남은 초
```

> `KEYS` 는 개발용입니다. 운영에서는 전체를 훑어 느립니다.

<br><br>

---

### 4-3. 이벤트 2개

| 이벤트 | 언제 | payload | 받는 곳 |
|---|---|---|---|
| `account.created` | 가입 (이메일 · 소셜 신규) | `{accountId, email, nickname}` | user → 프로필 생성 |
| `account.withdrawn` | 탈퇴 | `{accountId}` | user · pet · report · review · notification → 각자 데이터 삭제 |

---

**`account.created` 만 값을 나릅니다.**

다른 이벤트는 식별자만 담고 받는 쪽이 `/internal` 로 다시 읽는데, **auth 에는 `/internal`
API 가 없고 `nickname` 은 auth 에 저장되지도 않습니다.** 그래서 이것만 예외입니다.

> 소셜 가입은 `nickname: null` 로 나갑니다. 사용자가 프로필 설정에서 직접 입력합니다.

---

**발행은 outbox 를 거칩니다.**

```
가입 트랜잭션
  account INSERT
  outbox INSERT  {account.created}      ← 같은 트랜잭션
  커밋
    └──▶  OutboxCommitListener 가 카프카로 발행
          실패하면 OutboxRelay 가 5초마다 재시도, 10회 넘기면 포기 → 관리자 재발행
```

**"계정은 생겼는데 프로필이 안 생기는"** 상태가 원천 차단됩니다.
발행이 실패해도 outbox 행이 남아 있어 언젠가는 나갑니다.

---

**받는 이벤트는 없습니다.**

auth 가 다른 서비스의 변화에 반응할 일이 없습니다. `@KafkaListener` 가 0개이고
`processed_event` 테이블도 없습니다.

<br><br>

---

### 4-4. Flyway 스크립트

```
db/migration/service/
├── V20__auth.sql                              account · refresh_token_log 생성
├── V21__add_refresh_token_id_unique.sql       token_id UNIQUE
├── V22__add_token_revocation_and_login_id.sql tokens_valid_from · login_id
└── V23__replace_provider_unique_index.sql     provider_user_id 단독 UNIQUE
```

| 번호 | 왜 따로 |
|---|---|
| V21 | `findByTokenId` 가 `Optional` 인데 중복이 들어오면 예외. **인덱스가 없어 로그아웃마다 전체 스캔이던 것도 해결** |
| V22 | 토큰 일괄 폐기(`tokens_valid_from`)와 로그인 묶기(`login_id`). 기존 행은 `created_at` · 자기 `id` 로 채움 |
| V23 | 소셜 연결로 같은 `sub` 가 `(LOCAL, sub)` 와 `(GOOGLE, sub)` 두 모양이 될 수 있어 복합 UNIQUE 로는 못 막음 |

> **V20 을 고치지 않고 새 번호로 갑니다.** 한 번 실행된 스크립트는 체크섬이 기록돼
> 고치면 다음 기동이 실패합니다. 규칙은 `service-template` README 4-8 에 있습니다.

<br><br>

---

## 5. 코드 구조

4계층 규칙은 `service-template` README 8장에 있습니다. **여기서는 auth 의 실제 파일이
어디 있고 무엇을 하는지만** 봅니다.

<br><br>

---

### 5-1. 파일 트리

```
com.pawtrail.auth
│
├── AuthApplication.java
│
├── presentation/
│   ├── controller/
│   │   ├── AuthController              /api/v1/auth  — 가입 · 로그인 · 갱신 · 로그아웃 · /me · 비밀번호 · 탈퇴
│   │   ├── OAuthController             /api/v1/auth/oauth  — authorize · callback
│   │   └── AdminAccountController      /api/v1/admin/accounts  — outbox 목록 · 재발행
│   ├── request/
│   │   ├── SignupRequest · LoginRequest · PasswordChangeRequest
│   │   ├── EmailVerificationRequest    한 파일에 SendCode · VerifyCode · ... 중첩 record
│   │   └── validation/
│   │       ├── MaxBytes                @MaxBytes(72) — 비밀번호 바이트 길이
│   │       └── MaxBytesValidator
│   └── support/
│       └── ClientInfoFactory           HttpServletRequest 에서 IP · User-Agent 를 뽑음
│
├── application/
│   ├── service/                        ★11개 — 아래 5-2
│   ├── dto/
│   │   ├── input/                      SignupInput · LoginInput · PasswordChangeInput · EmailVerifyInput
│   │   │                               PasswordResetInput · OAuthCallbackInput · ClientInfo
│   │   └── output/                     AccountOutput · OutboxMessageOutput
│   └── support/
│       ├── AfterCommitExecutor         커밋 뒤에 실행 (Redis 쓰기는 롤백이 안 되므로)
│       └── VerificationCodeGenerator   6자리 난수
│
├── domain/
│   ├── model/
│   │   ├── Account                     계정. createLocal · createSocial · withdraw · changePassword · linkGoogle
│   │   └── RefreshTokenLog             이력. issue · revoke
│   ├── enums/
│   │   ├── AuthProvider                LOCAL · GOOGLE  — hasPassword()
│   │   └── AccountStatus               ACTIVE · WITHDRAWN
│   ├── repository/                     ★8개 — 아래 5-3
│   ├── provider/
│   │   ├── MailSender                  메일을 보낸다는 약속 (MailPurpose 중첩)
│   │   └── OAuthClient                 구글에서 사용자 정보를 가져온다는 약속
│   ├── event/payload/
│   │   ├── AccountCreatedEvent         {accountId, email, nickname}
│   │   └── AccountWithdrawnEvent       {accountId}
│   └── exception/
│       └── AuthErrorCode               16개
│
└── infrastructure/
    ├── config/
    │   ├── SecurityConfig              ★자기 보안 체인 — 아래 5-4
    │   ├── JwtEncoderConfig            RS256 키 → JwtEncoder · JwtDecoder. @ConfigurationProperties 등록도 여기
    │   ├── JwtProperties               app.jwt.*  — 비면 기동 실패
    │   ├── AuthProperties              app.auth.* — permit-all · 유예 · 쿠키
    │   ├── MailProperties              app.mail.*
    │   └── OAuthProperties             app.oauth.*
    ├── security/                       ★auth 에만 있는 폴더 (템플릿에 없음)
    │   ├── TokenProvider               토큰 발급
    │   ├── TokenReader                 토큰 읽기 (갱신 · 로그아웃용)
    │   ├── TokenType                   ACCESS · REFRESH — typ 값
    │   └── CookieFactory               쿠키 만들기 · 지우기
    ├── persistence/
    │   ├── *RepositoryImpl · *StoreImpl   ★8개 — 아래 5-3
    │   └── jpa/
    │       ├── AccountJpaRepository
    │       └── RefreshTokenLogJpaRepository
    └── provider/
        ├── external/
        │   ├── SmtpMailSender          Gmail SMTP
        │   ├── GoogleOAuthClient       토큰 교환 · id_token 검증
        │   └── dto/GoogleTokenResponse 구글 응답 형태
        └── internal/                   (비어 있음 — 우리 서비스를 안 부름)

src/main/resources/
├── application.yml                     세 줄
├── db/migration/service/V20 ~ V23
└── redis/
    ├── rotate-refresh-token.lua        갱신 — 옛 토큰 소비 · 새 토큰 활성화 · 유예 등록을 한 덩어리로
    └── consume-withdraw-code.lua       탈퇴 — 코드가 맞을 때만 지움
```

---

**템플릿에 없는 것 셋입니다.**

| 폴더 | 왜 auth 에만 |
|---|---|
| `infrastructure/security/` | 토큰을 **만드는** 서비스는 auth 뿐. 다른 13개는 헤더만 읽음 |
| `presentation/request/validation/` | `@MaxBytes` 가 필요한 곳이 지금은 여기뿐 |
| `resources/redis/` | Lua 스크립트. 여러 키를 원자적으로 다뤄야 하는 곳이 auth 에 둘 |

---

**비어 있는 것입니다.**

| 폴더 | 왜 |
|---|---|
| `domain/rule/` | 엔티티에 붙지 않는 판단이 없음. 규칙이 전부 `Account` 메서드 안에 있음 |
| `domain/event/` (인터페이스) | 판단 없이 그대로 발행하므로 `OutboxEventRecorder` 를 직접 씀 |
| `infrastructure/provider/internal/` | **우리 서비스를 한 번도 안 부름** |
| `infrastructure/message/kafka/` | 발행은 공통 모듈이 하고, 받는 것은 없음 |

<br><br>

---

### 5-2. 서비스 클래스 11개 — 누가 무엇을 하나

```
[ 누가 누구를 부르나 ]

AuthController ──────┬──▶  EmailVerificationService    가입 인증 코드
                     ├──▶  AuthService                 signup · login
                     │        └──▶  TokenIssueService   토큰 2개 발급 · Redis · 이력   (login 이 씀)
                     ├──▶  RefreshService              갱신 (Lua rotate)
                     │        └──▶  TokenRevokeService  복제 탐지 시 전부 폐기 (REQUIRES_NEW)
                     ├──▶  LogoutService               로그아웃
                     ├──▶  AccountService              /me · 비밀번호 변경
                     │        └──▶  TokenRevokeService  변경 후 전부 폐기 (REQUIRED)
                     ├──▶  PasswordResetService        재설정 코드 · 재설정
                     │        └──▶  TokenRevokeService  재설정 후 전부 폐기 (REQUIRED)
                     └──▶  WithdrawService             탈퇴 코드 · 탈퇴
                              └──▶  TokenRevokeService

OAuthController ─────▶  OAuthLoginService             authorize · callback
                              └──▶  TokenIssueService   AuthService.login 과 같은 것을 씀

AdminAccountController ▶  AdminOutboxService           목록 · 재발행
```

---

| 서비스 | 하는 일 | 트랜잭션 |
|---|---|---|
| `EmailVerificationService` | 가입 인증 코드 발송 · 확인 | 없음 (Redis 만) |
| `AuthService` | `signup` · `login` | `@Transactional` |
| `TokenIssueService` | 토큰 2개 발급 · Redis 저장 · 이력 · `last_login_at` | `MANDATORY` — 부르는 쪽에 묶여야 함 |
| `RefreshService` | 갱신 · 로테이션 · 복제 탐지 | `@Transactional` |
| `LogoutService` | Redis 삭제 · 이력 `revoked_at` | `@Transactional` |
| `AccountService` | `/me` · 비밀번호 변경 | `@Transactional` |
| `PasswordResetService` | 재설정 코드 · 재설정 | `@Transactional` |
| `WithdrawService` | 탈퇴 코드 · 탈퇴 · 이벤트 | `@Transactional` |
| `TokenRevokeService` | 계정의 토큰 전부 폐기 | **진입점 둘** — 아래 |
| `OAuthLoginService` | authorize · callback · 계정 매칭 | `@Transactional` |
| `AdminOutboxService` | 포기한 이벤트 목록 · 재발행 | 조회는 `readOnly` |

---

**`AuthService` 와 `AccountService` 를 나눈 기준입니다.**

| | 받는 것 | 하는 일 |
|---|---|---|
| `AuthService` | 이메일 · 비밀번호 | **"내가 누구인지 증명"** — 가입 · 로그인 |
| `AccountService` | 계정 ID (헤더에서) | **"이미 증명한 사람이 자기 것을 다룸"** — 조회 · 변경 |

---

**`TokenRevokeService` 에 진입점이 둘인 이유입니다.**

```java
revokeAllInNewTransaction()   REQUIRES_NEW   복제 탐지용 — 부른 뒤 예외를 던져야 하므로 먼저 커밋
revokeAll()                   REQUIRED       비밀번호 변경 · 재설정 · 탈퇴 — 같은 트랜잭션에서
```

```
복제 탐지                             비밀번호 변경
  폐기 → 401 예외                       폐기 → 계속 진행 → 200
  같은 트랜잭션이면 예외로 롤백돼          REQUIRES_NEW 면 바깥이 들고 있던 낡은 Account 가
  폐기가 없던 일이 됨                     tokens_valid_from 을 옛 값으로 덮어씀
  → REQUIRES_NEW 로 먼저 커밋            → REQUIRED 로 같은 인스턴스를 씀
```

> **별도 클래스여야 합니다.** 같은 클래스 안에서 부르면 프록시를 안 거쳐 전파 설정이 무시됩니다.

<br><br>

---

### 5-3. 저장소 8개 — 약속과 구현

| 약속 (`domain/repository`) | 구현 (`infrastructure/persistence`) | 저장소 | 무엇을 |
|---|---|---|---|
| `AccountRepository` | `AccountRepositoryImpl` → `AccountJpaRepository` | PostgreSQL | 계정 |
| `RefreshTokenLogRepository` | `…Impl` → `RefreshTokenLogJpaRepository` | PostgreSQL | 이력. `revokeAllActive` 는 벌크 UPDATE |
| `RefreshTokenStore` | `RefreshTokenStoreImpl` | Redis | `save` · `rotate`(Lua) · `findGrace` · `claim` |
| `EmailVerificationStore` | `…Impl` | Redis | 가입 인증 코드 · 통과 표시 |
| `PasswordResetStore` | `…Impl` | Redis | 재설정 코드 |
| `WithdrawStore` | `…Impl` | Redis | 탈퇴 코드. `consume` 은 Lua |
| `SendRateLimitStore` | `…Impl` | Redis | 발송 제한. `tryAcquire` · `recordSent` · `release` |
| `OAuthStateStore` | `…Impl` | Redis | state → nonce |

> **Redis 도 JPA 와 똑같이 약속과 구현으로 나눕니다.** 서비스는 `RedisTemplate` 을 모릅니다.
> `XxxStore` 가 Redis, `XxxRepository` 가 JPA 라는 것이 이름 규칙입니다.

---

**Lua 를 쓰는 곳 둘입니다.**

| 스크립트 | 왜 Lua |
|---|---|
| `rotate-refresh-token.lua` | 키 셋(옛 토큰 · 새 토큰 · 유예)을 **한 덩어리로** 바꿔야 합니다. 나누면 그 사이에 들어온 요청이 활성화 안 된 토큰을 받아 갑니다 |
| `consume-withdraw-code.lua` | 코드가 **맞을 때만** 지워야 합니다. `GETDEL` 은 값을 안 보고 지워 오타 한 번에 진짜 코드가 사라집니다 |

```lua
-- rotate-refresh-token.lua
local accountId = redis.call('GETDEL', KEYS[1])            -- refresh:{옛jti}   소비
if not accountId then return false end                     -- 이미 없으면 → 복제 또는 경합
redis.call('SET', KEYS[2], accountId, 'EX', ARGV[1])       -- refresh:{새jti}   활성화
redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])         -- refreshgrace:{옛jti}
return accountId
```

<br><br>

---

### 5-4. 보안 설정 — 왜 자기 체인을 정의하나

공통 모듈은 **모든 요청에 인증을 요구하는** 기본 체인을 줍니다. 그런데 auth 는
로그인·가입처럼 **인증 없이 열어야 하는 경로가 9개** 있습니다. 그래서 자기 체인을 만듭니다.

```
요청  ──▶  SecurityFilterChain (auth 가 직접 정의)
              │
              ├──▶  HeaderAuthenticationFilter        X-User-Id · X-User-Role 를 읽어 SecurityContext 를 채움
              │                                       (공통 모듈 것. 자기 체인이라 직접 등록함)
              │
              ├──▶  경로 규칙
              │       /internal/** · /actuator/**     permitAll
              │       app.auth.permit-all 9줄          permitAll     ← config 에서 읽음
              │       /api/v1/admin/**                hasRole(ADMIN)
              │       그 밖의 전부                     authenticated
              │
              ├──▶  csrf · formLogin · httpBasic 끔    쿠키 + 헤더 방식이라 스프링 로그인 폼을 안 씀
              ├──▶  세션 STATELESS                     JWT 라 세션을 안 만듦
              └──▶  401 · 403 을 CustomSecurityExceptionHandler 로   공통 응답 형식
```

---

**자기 체인을 정의하면 공통 모듈 것이 통째로 물러납니다.**

그래서 공통 체인이 하던 일을 **전부 다시 넣어야 합니다.**

| 다시 넣은 것 | 빠뜨리면 |
|---|---|
| `HeaderAuthenticationFilter` | `GET /me` 가 누구인지 모름 → 전부 401 |
| `/api/v1/admin/**` → `hasRole("ADMIN")` | 관리자 API 가 USER 에게 열림 (게이트웨이가 한 번 막지만 이중화가 반쪽) |
| `/internal/** · /actuator/**` permitAll | 헬스체크가 401 |
| `CustomSecurityExceptionHandler` | 401·403 응답이 공통 형식이 아니게 됨 |
| `AuthenticationManager` 빈 | 기동 로그에 `Using generated security password` 가 찍힘 (공통 모듈 0.0.7 이 처리) |

> **`permit-all` 목록을 config 에서 읽는 이유** — 게이트웨이도 그렇게 하고 있어서입니다.
> 같은 목록이 두 곳에 있는데 한쪽만 코드에 박혀 있으면 고칠 때 빠뜨리기 쉽습니다.
> 설정을 못 받으면 목록이 비어 **전부 401** 이 되는데, 그것을 *"열 경로가 없다"* 가 아니라
> *"설정을 못 받았다"* 로 보고 **기동 시점에 막습니다.**

<br><br>

---

## 6. 설정값

설정 4계층 규칙은 `service-template` README 6장에 있습니다.
**auth 가 쓰는 값이 무엇이고 어디 있는지만** 봅니다.

<br><br>

---

### 6-1. config 저장소의 auth-service.yml

```yaml
# 2계층 — auth-service.yml   (모든 환경 공통)
server:
  port: 8081

spring:
  datasource:
    url: jdbc:postgresql://${app.datasource.host}:5432/auth_db
    username: auth_svc
  mail:
    host: smtp.gmail.com
    port: 587
    username: pawtrail.noreply@gmail.com
    password: ${AUTH_MAIL_PASSWORD}                    # ← 환경변수
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
      mail.smtp.connectiontimeout: 5000
      mail.smtp.timeout: 5000
      mail.smtp.writetimeout: 5000

app:
  outbox:
    relay:
      enabled: true                                     # 이벤트를 발행하므로

  jwt:
    issuer: pawtrail-auth
    private-key-b64: ${AUTH_JWT_PRIVATE_KEY_B64}        # ← 환경변수
    access-expiry: 30m
    refresh-expiry: 14d
    claim:
      account-id: sub
      role: role
      type: typ

  auth:
    permit-all:                                         # 게이트웨이와 같은 9줄
      - /api/v1/auth/signup
      - /api/v1/auth/login
      - /api/v1/auth/refresh
      - /api/v1/auth/logout
      - /api/v1/auth/oauth/**
      - /api/v1/auth/password/reset-request
      - /api/v1/auth/password/reset
      - /api/v1/auth/email/verify-request
      - /api/v1/auth/email/verify
    rotation-grace: 30s
    cookie:
      domain: ""
      refresh-path: /api/v1/auth

  mail:
    from: pawtrail.noreply@gmail.com

  oauth:
    google:
      client-id: 1234567890-abc.apps.googleusercontent.com   # 비밀 아님
      client-secret: ${AUTH_OAUTH_GOOGLE_CLIENT_SECRET}      # ← 환경변수
```

```yaml
# 3계층 — application-local.yml   (local 만)
app:
  auth:
    cookie:
      secure: false                    # http://localhost 라 Secure 를 못 씀
  oauth:
    frontend-base-url: http://localhost:5173
    google:
      redirect-uri: http://localhost:8080/api/v1/auth/oauth/google/callback
```

```yaml
# 3계층 — application-dev.yml · application-prod.yml
app:
  auth:
    cookie:
      secure: true
```

---

**환경마다 다른 값 셋입니다.**

| 값 | local | dev · prod |
|---|---|---|
| `cookie.secure` | `false` | `true` |
| `frontend-base-url` | `http://localhost:5173` | `https://<도메인>` |
| `redirect-uri` | `http://localhost:8080/...` | `https://<도메인>/...` |

> `cookie.secure` 에 **2계층 기본값을 두지 않습니다.** 두면 3계층을 안 만든 환경에서
> 조용히 그 값으로 도는데, `false` 면 배포가 위험하고 `true` 면 로컬이 안 됩니다.
> 없으면 기동 시점에 터지는 편이 낫습니다.

<br><br>

---

### 6-2. 환경변수와 config 가 갈리는 기준

| | config 저장소 (공개) | 환경변수 |
|---|---|---|
| 개인키 | | ✓ 새면 누구나 토큰을 위조 |
| 공개키 | ✓ (게이트웨이 것) | |
| 구글 클라이언트 ID | ✓ 주소창에 그대로 뜨는 값 | |
| 구글 클라이언트 시크릿 | | ✓ |
| Gmail 앱 비밀번호 | | ✓ |
| DB 비밀번호 | | ✓ |
| DB 호스트 | | ✓ 사람마다 다름 (`localhost` / EC2) |
| 만료 시간 · 유예 · 경로 | ✓ | |

**기준은 "새면 무엇을 할 수 있나" 입니다.** 확인만 되고 만들 수는 없는 값(공개키 · 클라이언트 ID)은
공개돼도 무해하므로 config 에 둡니다.

> **`${SERVICE_DB_PASSWORD:1234}` 처럼 기본값을 박지 않습니다.** 환경변수를 빠뜨려도
> 붙어 버려 누락이 영영 안 드러납니다.

<br><br>

---

### 6-3. 검증이 걸린 값 — 비면 기동이 안 됩니다

`JwtProperties` · `AuthProperties` 가 **컴팩트 생성자에서 검증합니다.**

| 클래스 | 검증하는 것 | 왜 비면 안 되나 |
|---|---|---|
| `JwtProperties` | `issuer` · `privateKeyB64` | 토큰을 못 만듦 |
| | `accessExpiry` · `refreshExpiry` > 0 | `0s` 면 발급 즉시 만료. Lua 의 `EX 0` 이 오류 |
| | `claim.accountId` · `role` · `type` | **빈 문자열이 null 보다 위험** — 이름 없는 항목이 든 토큰이 만들어지고 게이트웨이가 401 만 냄 |
| `AuthProperties` | `permitAll` 비어 있지 않음 | 비면 로그인이 401. "열 경로가 없다" 가 아니라 "설정을 못 받았다" |
| | `cookie.refreshPath` | null 이면 Path 가 안 붙어 **로그아웃해도 쿠키가 안 지워짐** |

---

**값을 새로 넣거나 검증을 추가하면 세 곳을 같이 봅니다.**

```
config/auth-service.yml                          실제 값
auth-service/src/test/resources/application.yml  테스트용 사본   ← 놓치기 쉬움
JwtProperties · AuthProperties                   검증
```

> **테스트는 설정 서버를 꺼서 config 값이 하나도 안 내려옵니다.** config 에만 넣고
> 테스트 yml 을 안 고치면 `contextLoads` 가 검증에 걸려 빌드가 실패합니다.
> 실제로 `app.jwt.claim.type` 을 넣을 때 그랬습니다.

---

**테스트 yml 의 RSA 키는 따로 만든 것입니다.**

실제 서명 키를 공개 저장소에 커밋하지 않으려고 **테스트 전용 키**를 씁니다.
그 키로 만든 토큰을 받아 주는 서버가 없어 공개돼도 무해합니다.

<br><br>

---

## 7. 운영

<br><br>

---

### 7-1. 관리자 지정

**관리자 API 가 없습니다.** DB 에서 직접 바꿉니다.

**macOS · Windows 공통**

```bash
docker compose exec postgres psql -U auth_svc -d auth_db -c \
  "UPDATE account SET role = 'ADMIN' WHERE email = 'me@example.com';"

docker compose exec postgres psql -U auth_svc -d auth_db -c \
  "SELECT email, role FROM account WHERE role = 'ADMIN';"
```

> **바꾼 뒤 반드시 다시 로그인합니다.** 토큰 안의 `role` 은 발급 시점 값이라
> 옛 쿠키로는 여전히 `USER` 로 나가 403 이 납니다.

---

**Flyway 로 하지 않는 이유입니다.**

| Flyway | psql |
|---|---|
| 모든 환경에서 똑같이 실행됨. **계정 UUID 는 환경마다 다름** | 환경마다 사람이 한 번 |
| 한 번 적용되면 못 고침. 관리자를 바꿀 때마다 V24 · V25 가 쌓임 | 흔적이 안 남음 |
| **스키마 이력에 운영 기록이 섞임** | |

`REVOKE CONNECT` 같은 것은 *"DB 를 세울 때마다 반드시"* 라 init 스크립트에 있고,
관리자 지정은 *"사람이 판단해 환경마다 한 번"* 이라 손으로 합니다.

<br><br>

---

### 7-2. 관리자 API 부르기

**반드시 게이트웨이(`:8080`)를 거칩니다.** `X-User-Role: ADMIN` 헤더는 게이트웨이만 넣어 줍니다.

```
8081 직결          401   auth 가 냄.   traceId 있음
8080 · USER       403   게이트웨이가 냄. traceId null    ← 게이트웨이가 먼저 막음
8080 · ADMIN      200
```

> `traceId` 로 **어느 층이 막았는지** 알 수 있습니다. 게이트웨이가 만든 응답은
> 도메인 서비스까지 안 가서 `traceId` 가 `null` 입니다.

---

**curl 로**

```bash
# ADMIN 계정으로 로그인해 쿠키를 받은 뒤
curl -b cookies.txt http://localhost:8080/api/v1/admin/accounts/outbox
curl -b cookies.txt -X POST http://localhost:8080/api/v1/admin/accounts/outbox/{id}/retry
```

**브라우저 콘솔로**

```
① http://localhost:8080/api/v1/auth/me 를 연다   ← 반드시 8080 페이지에서
② F12 → Console
③ allow pasting 을 직접 타이핑 (붙여넣기 잠금 해제)
④ fetch('/api/v1/admin/accounts/outbox').then(r => r.json()).then(console.log)
```

> **8080 이 아닌 페이지에서 열면 상대 경로가 그 출처로 나갑니다.** 프론트가 없어
> `/login/success` 는 오류 페이지가 되고 거기서 열면 `chrome-error://` 로 붙어 실패합니다.

---

**Swagger 로는 부를 수 없습니다.**

Swagger UI 는 `:8081` 에서 뜨고 **Try it out 도 8081 로 쏩니다.** 게이트웨이를 안 거쳐
헤더가 없으므로 401 입니다. 관리자 화면은 프론트에 만듭니다.

<br><br>

---

### 7-3. 이벤트를 다시 보내야 할 때

```
① GET /api/v1/admin/accounts/outbox 로 목록 확인
        │
        ├── 비어 있음   →  문제 없음. 끝
        │
        └── 있음  →  lastError 를 봄
                       TimeoutException     →  ② 카프카가 살아 있는지 확인하고 retry
                       직렬화 · 코드 오류     →  코드를 먼저 고치고 배포한 뒤 retry
```

---

**직접 확인할 때입니다.**

```bash
# 포기한 건 (retry_count 10 이상, 미발행)
docker compose exec postgres psql -U auth_svc -d auth_db -P pager=off -c \
  "SELECT id, topic, retry_count, last_error, created_at
   FROM outbox WHERE published_at IS NULL ORDER BY created_at;"

# 카프카에 실제로 들어갔는지
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic account.created --from-beginning --max-messages 5
```

> `-P pager=off` 가 없으면 결과가 넓을 때 **멈춘 것처럼 보입니다.**
> Kafka UI(`tools` 프로파일, `:9000`)로 보는 편이 편합니다.

---

**`retry_count` 를 손으로 올려 테스트할 때는 auth 를 내립니다.**

발행 중인 행에 `FOR UPDATE SKIP LOCKED` 잠금이 걸려 있어 **auth 가 떠 있으면 UPDATE 가
잠금 대기로 멈춥니다.** 잠깐 내리고 하면 즉시 됩니다.

<br><br>

---

### 7-4. 키 페어를 새로 만들 때

**로컬 키가 노출됐거나 배포용 키를 만들 때**입니다.

**macOS**

```bash
mkdir -p ~/pawtrail-keys && cd ~/pawtrail-keys
openssl genpkey -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:2048
openssl rsa -in private.pem -pubout -out public.pem

# 개인키 → Base64 한 줄 → 클립보드
base64 -i private.pem | tr -d '\n' | pbcopy
```

**Windows (PowerShell)**

```powershell
mkdir C:\Tour_Prj\pawtrail-keys; cd C:\Tour_Prj\pawtrail-keys
openssl genpkey -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:2048
openssl rsa -in private.pem -pubout -out public.pem

# 개인키 → Base64 한 줄 → 클립보드
[Convert]::ToBase64String([IO.File]::ReadAllBytes("private.pem")) | Set-Clipboard
```

---

**갈아야 하는 곳이 둘입니다.**

| 무엇 | 어디 | 어떻게 |
|---|---|---|
| 개인키 | 환경변수 `AUTH_JWT_PRIVATE_KEY_B64` | 클립보드 값을 붙임. **팀원 전원** |
| 공개키 | config `gateway-server.yml` 의 `app.jwt.public-key` | `public.pem` 내용을 블록 스칼라로. **들여쓰기 주의** |

```yaml
# config/gateway-server.yml
app:
  jwt:
    public-key: |
      -----BEGIN PUBLIC KEY-----
      MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
      -----END PUBLIC KEY-----
```

> **짝이 어긋나면 전 요청이 401 이 됩니다.** 둘을 같이 바꾸고 게이트웨이를 재시작합니다.
> 기존 로그인은 전부 풀립니다 — 옛 키로 서명된 토큰이라 새 공개키로 검증이 안 됩니다.
>
> **키 값을 채팅에 붙여넣지 않습니다.** 클립보드로 환경변수에 바로 넣고 "넣었다" 만 알립니다.
> 값이 맞는지는 빌드와 로그인이 알려 줍니다.

<br><br>

---

### 7-5. 배포 순서 — auth 를 먼저

**auth 와 게이트웨이를 함께 바꿀 때 순서가 있습니다.**

```
① auth 배포             새 토큰에 typ 이 들어감
        │
        ▼
② 30분 기다림           옛 액세스 토큰(typ 없음)이 전부 만료됨
        │
        ▼
③ 게이트웨이 배포        typ 검사가 켜짐
```

> 반대로 하면 **②가 지나기 전에 발급된 토큰이 전부 401** 이 됩니다.
> 지금은 로컬뿐이라 문제가 없지만 **배포에서 처음 겪을 자리**입니다.

---

**배포용으로 바꾸는 것입니다.**

| 무엇 | 로컬 | 배포 |
|---|---|---|
| RS256 키 | 로컬 쌍 | **새 쌍** — 로컬 개인키가 각자 컴퓨터에 돌아다니므로 |
| `redirect-uri` | `http://localhost:8080/...` | `https://<도메인>/...` — **구글은 IP 와 HTTP 를 거부** |
| `frontend-base-url` | `http://localhost:5173` | `https://<도메인>` |
| 구글 앱 상태 | Testing (등록된 사용자만) | **게시** — 안 하면 심사위원이 로그인 못 함 |
| springdoc | 켜짐 | **끔** — API 목록이 바깥에 노출됨 |

<br><br>

---

## 8. 왜 이렇게 만들었나

**코드를 고치기 전에 읽으면 "이거 왜 이렇게 돼 있지" 가 줄어듭니다.**
각 항목은 문제 → 고른 것 → 버린 것 순서입니다.

<br><br>

---

### 8-1. 인증 방식

**Keycloak 같은 기성품을 안 쓴 이유**

| 고름 | 버림 |
|---|---|
| 직접 구현 | Keycloak |
| auth 가 얇은 프록시로 전락하지 않음 | Keycloak DB 가 따로 관리됨. 토큰을 바디로 주므로 쿠키로 바꿔 심는 래퍼가 필요 |
| 이 프로젝트의 승부처(판정 · MSA 경계 · 배포)에 인증이 없어 시간을 쓸 이유가 약함 | 리프레시 토큰 Redis 와 Keycloak 세션이 겹쳐 관리 주체가 둘 |

---

**액세스 토큰을 쿠키에 두는 이유**

`localStorage` 는 XSS 에 통째로 털리고, 쿠키는 `SameSite=Strict` 로 CSRF 를 막습니다.
nginx 가 프론트와 API 를 같은 도메인으로 서빙해 `Strict` 를 쓸 수 있습니다.

---

**서비스 간 호출에 토큰을 안 붙이는 이유**

VPC 내부망 + 보안그룹 격리로 갑니다. 제로 트러스트로 가면 서비스마다 토큰 발급·검증이
붙어 복잡도가 급증합니다. 이 규모에서는 부적합하다고 판단했습니다.

---

**JWT 라이브러리가 `spring-security-oauth2-jose` 인 이유**

| 고름 | 버림 |
|---|---|
| `NimbusJwtEncoder` | JJWT |
| **게이트웨이가 같은 라이브러리로 검증**하고 있어 알고리즘·형식이 어긋날 여지가 적음 | API 가 간결하지만 `jjwt-jackson` 이 Jackson 2 를 끌고 옴. 우리는 Boot 4 + Jackson 3 |

<br><br>

---

### 8-2. 토큰

**리프레시 토큰도 JWT 인 이유**

`jti` 가 필요한데 JWT 는 그것이 규격에 있는 자리이고, 만료를 토큰이 들고 있어
**Redis 가 날아가도 만료된 토큰은 통하지 않습니다.**

---

**`sub` · `role` 이라는 claim 이름**

`sub` 는 JWT 규격(RFC 7519)의 표준 항목이라 `Jwt.getSubject()` 와 로깅 도구가 그 자리를 봅니다.
`accountId` 로 바꾸면 이름은 명확하지만 표준 자리를 비워 두게 됩니다.

---

**로테이션 + 유예 30초**

| 고름 | 버림 |
|---|---|
| 쓸 때마다 교체, 30초 안 재사용은 경합으로 봄 | (A) 재사용을 그냥 401 — 탐지 이득이 사라짐 |
| 30초 밖 재사용은 복제로 보아 전부 폐기 | (B) 유예 없이 전부 폐기 — 탭 두 개 = 전체 로그아웃 |

30초는 Okta 기본값이고 Cognito 는 최대 60초입니다. 10초는 *"응답 유실 후 재시도"* 를 못 덮습니다.

---

**Lua 스크립트를 쓴 이유**

처음에는 `claim` 과 `saveGrace` 를 따로 불렀는데 **병렬 검증에서 두 번 깨졌습니다.**
그 사이가 5~10ms 였고, 줄여서 0.2ms 로 만들어도 또 깨졌습니다. 키 셋을 한 덩어리로
바꾸는 방법은 Lua 뿐입니다.

> 교훈 — *"창이 얼마나 좁은가"* 를 말할 때는 그 사이에 무엇이 들어 있는지 코드로 세어 봐야 합니다.

---

**`tokens_valid_from` — 지우지 않고 폐기**

| 고름 | 버림 |
|---|---|
| 계정에 시각 하나. 그 앞의 토큰은 갱신에서 거부 | 계정별 jti 목록(Set) — 발급·삭제·만료를 두 곳에서 관리, TTL 로 사라진 jti 가 Set 에 남음 |
| 지울 것이 없고 "모든 기기에서 로그아웃" 이 한 줄 | 키를 `refresh:{accountId}:{jti}` 로 바꾸고 `SCAN` — 전체 키를 훑음 |

**값은 초 단위로 자릅니다.** JWT 의 `iat` 가 초 단위라 안 자르면 비밀번호를 바꾸고
바로 로그인해 받은 토큰이 30분 뒤 거부됩니다.

<br><br>

---

### 8-3. 계정

**`SUSPENDED` 가 없는 이유**

계정을 막아야만 풀리는 문제가 하나도 없습니다. 악성 후기는 후기를 지우고 잘못된 제보는
반려합니다. 값만 열어 두면 정지·해제 API · 컬럼 셋 · 에러 코드 · 문서가 함께 필요한데
**넣을 방법이 없는 값은 죽은 값**입니다.

---

**탈퇴해도 행을 안 지우는 이유**

| 지우면 |
|---|
| 이벤트 소비가 실패했을 때 *"이 accountId 가 정말 탈퇴했나"* 를 확인할 근거가 없음 |
| `refresh_token_log` 가 누구 것인지 모르는 고아가 됨 |
| "전진 복구" 의 전제 — 마저 지우려면 "지워야 할 대상" 이 남아 있어야 함 |

**식별자는 끊습니다.** 안 끊으면 같은 이메일로 영영 재가입이 안 됩니다.

---

**`nickname` 을 안 담는 이유**

`user_profile` 의 컬럼이라 auth 가 user 를 불러야 합니다. `hasPet` · `defaultPetId` 를 뺀 근거가
정확히 그것이었는데 `nickname` 만 남아 있으면 기준이 반쪽입니다.
**결과로 auth 는 다른 서비스를 한 번도 안 부르는 서비스가 됐습니다.**

---

**닉네임 중복을 허용하는 이유**

금지하면 가입 때 검사해야 해서 **auth 가 user 를 동기 호출하게 되고 이벤트 방식이 무너집니다.**
닉네임이 나오는 자리가 후기 작성자 표시 하나뿐이라 사칭 유인도 없습니다.

---

**`WITHDRAWN` 판단을 리포지터리가 아니라 서비스에서 하는 이유**

상황마다 처리가 다릅니다.

| 상황 | 처리 |
|---|---|
| 로그인 | "탈퇴한 계정입니다" |
| 가입 중복 검사 | 막아야 함 (이제는 이메일이 치환돼 안 걸림) |
| 비밀번호 재설정 | 조용히 200 |

리포지터리가 `findActiveByEmail` 로 걸러 버리면 *"없음"* 과 *"탈퇴함"* 이 구분되지 않습니다.

---

**팩터리 둘 (`createLocal` · `createSocial`)**

로컬과 소셜이 채우는 필드가 다릅니다. 팩터리로 나누면 *"소셜인데 `password_hash` 가 있는"*
조합을 애초에 만들 수 없고, **팩터리 안에서 검증하므로 잘못 부르면 `IllegalArgumentException`** 이
납니다. 이것은 프로그래밍 실수라 `CustomException`(400) 이 아니라 500 이 맞습니다.

<br><br>

---

### 8-4. 소셜 로그인

**구글만인 이유**

| 제공자 | 문제 |
|---|---|
| 카카오 | 이메일을 받으려면 비즈 앱 전환 + 개인정보 심사. **승인 일정을 통제할 수 없음** |
| 네이버 | 검수 전에는 등록된 아이디만 로그인 |
| 구글 | `email` 스코프가 기본. 사용자가 거부할 개념이 없어 **이메일이 항상 확보됨** |

SES 대신 Gmail SMTP 를 고른 것과 같은 이유입니다 — 승인 일정을 우리가 통제할 수 없는 것은 씁니다.

---

**`spring-boot-starter-oauth2-client` 를 안 쓴 이유**

그 라이브러리의 값어치는 `oauth2Login` 필터가 콜백을 받아 세션을 만드는 것인데, **우리는 우리 JWT 를
쿠키에 심고 프론트로 302 해야 해서 마지막 단계를 바꿔야 합니다.** 그러면 내부 구조를 알아야 하고
코드가 줄지도 않습니다. `MailSender` ↔ `SmtpMailSender` 와 같은 모양으로 `RestClient` 직접 호출이 낫습니다.

---

**같은 이메일의 LOCAL 계정에 자동 연결하는 이유**

자동 연결이 위험한 경우는 *"기존 계정을 만든 사람이 그 이메일의 진짜 주인이 아닐 때"* 인데,
**LOCAL 가입도 이메일 인증을 거치므로 양쪽 다 주인이 확인된 상태**입니다.
거부하면 그 사용자는 앞으로도 계속 구글 로그인을 못 씁니다.

---

**`client-id` 를 config 에 그대로 두는 이유**

authorize URL 에 실려 **브라우저 주소창에 뜨는 값**이고, 남이 알아도 등록된 redirect URI 로만
돌아가 악용이 안 됩니다. 공개키를 config 에 둔 판단과 같습니다.

---

**구글 고정값(토큰 URL · JWKS URL)을 코드 상수로 둔 이유**

`redirect-uri` 는 우리가 정해 콘솔에 등록하는 값이고 `token-uri` 는 구글이 정한 값입니다.
한 블록에 섞으면 *"이 중에 내가 고쳐도 되는 게 뭐지"* 를 매번 판단하게 됩니다.

<br><br>

---

### 8-5. 메일

**Gmail 앱 비밀번호 · 개인 계정인 이유**

SES 는 샌드박스에서 검증 주소로만 발송되고 프로덕션 승인 일정을 통제할 수 없습니다.
Gmail 개인 계정은 하루 500통이라 심사에는 넉넉합니다.

---

**HTML 메일을 텍스트 블록으로 만드는 이유**

넣을 것이 코드 6자리 하나라 20줄이면 끝납니다. 템플릿 엔진을 넣으면 의존성만 늡니다.
**메일 HTML 은 웹과 규칙이 달라** `<style>` 을 무시하는 클라이언트가 있어 인라인 CSS 만 씁니다.

---

**발송 제한이 쿨다운 60초 + 시간당 5통인 이유**

하나만으로는 부족합니다. 쿨다운만 있으면 주소를 바꿔 가며 부르는 것을 못 막고,
상한만 있으면 그 한도까지 순식간에 몰아 보냅니다. **하루 500통은 생각보다 적어** 실제 위험입니다.

**용도별로 따로 셉니다** (`signup` · `pwreset` · `withdraw`). 한 키로 세면 가입 인증을
5번 받은 사람이 한 시간 동안 탈퇴를 못 합니다.

---

**Redis 부수효과를 커밋 뒤로 미루는 이유**

Redis 는 롤백 대상이 아닙니다. 트랜잭션 안에서 `emailverified` 를 지우고 DB 저장이 실패하면
**계정은 안 생겼는데 인증 표시만 사라져** 다시 인증해야 합니다.
`AfterCommitExecutor` 가 그 자리이며 공통 모듈의 `OutboxCommitListener` 와 같은 방식입니다.

<br><br>

---

### 8-6. 여러 번 깨진 자리 — 경합

**이 서비스에서 실제로 깨진 경합이 넷입니다.** 전부 *"확인과 실행 사이의 틈"* 이었습니다.

| 어디 | 무엇이 | 고친 방법 |
|---|---|---|
| 발송 제한 | `canSend` 와 `recordSent` 사이에 SMTP. 병렬 10발이 전부 통과 | `setIfAbsent` 로 쿨다운 키 선점 |
| 갱신 | `claim` 과 `saveGrace` 사이. 병렬 2발이 `200/401` | Lua 로 키 셋 원자화 |
| 탈퇴 코드 | `findCode` 와 `deleteCode` 사이. 같은 코드가 두 번 통과 | Lua 로 조건부 삭제 |
| outbox 발행 | 리스너와 Relay 가 같은 행을 집음. 같은 이벤트가 카프카에 2건 | `FOR UPDATE SKIP LOCKED` (공통 모듈 0.0.8) |

> **순차 호출로는 절대 안 드러납니다.** `ForEach-Object -Parallel` 로 때려야만 보입니다.
> 같은 부류를 또 만나면 **검증 방법부터 정하고** 시작합니다.

<br><br>

---

## 9. 막히기 쉬운 자리

**실제로 겪은 것만** 적었습니다.

<br><br>

---

### 9-1. 요청이 401 · 403 · 503 일 때

| 증상 | 원인 | 확인 |
|---|---|---|
| `GET /me` 가 401 | **8081 로 직접 불렀음.** 헤더는 게이트웨이만 넣어 줌 | 8080 으로 |
| 로그인이 401 | 게이트웨이나 auth 의 `permit-all` 에 그 경로가 빠짐 | 게이트웨이 로그에 `토큰 쿠키가 없습니다` 면 게이트웨이, auth 로그에 `인증 실패` 면 auth |
| 관리자 API 가 403 | `UPDATE role='ADMIN'` 만 하고 재로그인 안 함 | 토큰의 `role` 은 발급 시점 값 |
| 관리자 API 가 401 | 8081 직결 | `traceId` 가 있으면 auth 가 낸 것 |
| 8080 이 503 | 게이트웨이가 유레카에서 auth 를 못 찾음 | 30초 기다림. `http://localhost:8761` 에 `AUTH-SERVICE` 가 있는지 |
| 전 요청이 401 | **개인키와 공개키가 짝이 아님** | 키를 새로 만들었으면 config 의 공개키도 바꿨는지 |

<br><br>

---

### 9-2. 기동이 안 될 때

| 로그 | 원인 |
|---|---|
| `UnknownHostException: ${DB_HOST}` | 환경변수 `DB_HOST` 없음 |
| `password authentication failed for user "auth_svc"` | `SERVICE_DB_PASSWORD` 가 `infra/.env` 와 다름. **계정이 없으면 `role does not exist`** 라 이것은 비밀번호 문제 |
| `JwtProperties` 에서 `IllegalArgumentException` | `AUTH_JWT_PRIVATE_KEY_B64` 없음, 또는 config 의 `app.jwt.*` 가 안 내려옴 |
| `AuthProperties` 에서 `IllegalStateException: permit-all` | config 를 못 받음. **설정 서버가 떠 있는지** |
| `NoSuchBeanDefinitionException: JavaMailSender` | config 에 `spring.mail.host` 가 없음 — 그 값의 존재로 자동 설정이 켜짐 |
| `Migration checksum mismatch` | 이미 실행된 V2x 파일을 고쳤음. 로컬 DB 를 지우고 다시 |
| `Schema-validation: missing column` | 엔티티에 필드를 추가하고 마이그레이션을 안 만듦 |
| 포트가 8080 으로 뜸 | config 의 `auth-service.yml` 을 못 찾음. 파일명이 `spring.application.name` 과 같은지 |

<br><br>

---

### 9-3. 메일이 안 올 때

| 증상 | 원인 |
|---|---|
| 200 인데 메일이 안 옴 | **스팸함.** 개인 Gmail 발신이라 거기로 갈 수 있음 |
| 200 인데 메일이 안 옴 (스팸함에도) | 주소 오타. SMTP 는 받아들인 시점에 성공으로 보고 **반송은 나중에 발신 계정으로 옴** |
| `535 Authentication failed` | 앱 비밀번호를 **띄어쓰기 포함해서** 넣었음. 16자리 붙여서 |
| `535` (붙여도) | 앱 비밀번호가 아니라 계정 비밀번호를 넣음. **2단계 인증을 켜야 앱 비밀번호 메뉴가 나옴** |
| 429 `MAIL_SEND_COOLDOWN` | 60초 안 재요청. 테스트 중 자주 걸림 — Redis 에서 `mailcooldown:*` 를 지우면 됨 |
| 재설정은 200 인데 메일이 안 옴 | **의도된 동작일 수 있음.** 계정이 없거나 소셜 계정이면 조용히 200 |

```bash
# 발송 제한 풀기 (테스트용)
docker compose exec redis redis-cli --scan --pattern 'mail*' | xargs docker compose exec -T redis redis-cli DEL
```

<br><br>

---

### 9-4. 구글 로그인이 안 될 때

| 증상 | 원인 |
|---|---|
| 구글 화면에서 `redirect_uri_mismatch` | 콘솔에 등록한 URI 와 config 의 `redirect-uri` 가 한 글자라도 다름 |
| 구글 화면에서 `access_denied` (테스트 사용자) | 앱이 Testing 상태인데 그 구글 계정이 등록 안 됨 |
| 콜백이 `/login/error?reason=FAILED` | auth 로그를 봄. `invalid_client` 면 시크릿, `상태 쿠키가 없거나` 면 아래 |
| 콜백 URL 을 복사해 다시 열면 FAILED | **정상.** `state` 가 1회용이고 `nonce` 가 재사용을 막음 |
| 시크릿 창에서 콜백 URL 을 열면 FAILED | **정상.** `oauth_state` 쿠키가 없어 거부됨 |
| 정상 로그인인데 FAILED, 로그에 `상태 쿠키가 없거나 값이 다릅니다` | `oauth_state` 쿠키가 `Strict` 로 만들어짐. **`Lax` 여야 함** — 콜백은 구글에서 오는 요청 |
| `isNew` 가 예상과 다름 | **다른 브라우저는 다른 구글 계정**일 수 있음. 크롬과 엣지가 각자 로그인돼 있음 |
| 쿠키 목록에 `refresh_token` 이 안 보임 | 5173 페이지에서 보고 있음. `Path=/api/v1/auth` 라 **8080 페이지를 한 번 열어야** 목록에 뜸 |

<br><br>

---

### 9-5. 테스트 데이터를 만질 때

| 하려는 것 | 주의 |
|---|---|
| Redis 를 비움 (`FLUSHDB`) | `emailverified:` 도 사라짐. **인증 뒤에 비우면 가입이 막힘** |
| 계정을 지움 | `refresh_token_log` → `outbox` → `account` 순서. FK 는 없지만 정리하는 습관 |
| 소셜 계정을 흉내 냄 | `auth_provider` 와 `provider_user_id` 만 바꿈. **`password_hash` 를 NULL 로 지우면 로그인이 안 되어 토큰을 못 받음** |
| 탈퇴 계정을 흉내 냄 | `status` 만 `WITHDRAWN` 으로. `email` 이나 `provider_user_id` 를 지우면 조회에 안 걸려 신규 가입으로 빠짐 |
| `retry_count` 를 올림 | **auth 를 내리고.** 발행 중 행에 잠금이 걸려 있음 |
| 병렬 검증 | 순차로는 안 드러남. `1..10 \| ForEach-Object -Parallel { ... } -ThrottleLimit 10` |

```sql
-- 소셜 계정 흉내 (되돌리기 쉽게 password_hash 는 그대로)
UPDATE account SET auth_provider='GOOGLE', provider_user_id='test-sub-123' WHERE email='...';
UPDATE account SET auth_provider='LOCAL',  provider_user_id=NULL           WHERE email='...';
```

<br><br>

---

### 9-6. 코드를 고칠 때

| 하려는 것 | 주의 |
|---|---|
| `Properties` 에 검증 추가 | **테스트 yml 에도 값을 넣어야** `contextLoads` 가 통과 |
| 새 `@ConfigurationProperties` 클래스 | **`JwtEncoderConfig` 의 `@EnableConfigurationProperties` 목록**에 넣어야 빈이 됨. 이름이 JWT 인데 전부 거기 있음 |
| 쿠키를 하나 더 만듦 | *"바깥에서 우리 주소로 오는 요청에 실려 와야 하나"* 를 물음. 그러면 `Strict` 로는 안 됨 |
| 서비스 안에서 `@Transactional` 메서드를 부름 | **프록시를 안 거쳐 전파 설정이 무시됨.** 별도 빈으로 |
| `revokeAllActive` 같은 벌크 UPDATE | `clearAutomatically = true` 를 쓰면 **직전에 고친 `Account` 변경이 사라짐** |
| Redis 를 트랜잭션 안에서 씀 | 롤백이 안 됨. `AfterCommitExecutor` 로 |
| 에러 코드를 새로 만듦 | *"프론트가 할 일이 다른가"* 가 기준. 상태 코드가 같다고 합치지 않고 다르다고 나누지도 않음 |
| 이메일 관련 응답을 만듦 | **재설정 계열은 응답이 갈리는 자리를 하나도 만들지 않음.** 가입 계열은 반대로 알려 줌 |

<br><br>

---

## 10. 아직 안 한 것

**시점이 정해진 것과 판단만 남은 것**으로 나뉩니다.

<br><br>

---

### 10-1. 시점이 정해진 것

| 언제 | 무엇 |
|---|---|
| **nginx 를 붙일 때** | `ip_address` 채우기 — 게이트웨이가 `X-Forwarded-For` 를 넣게 하고 **몇 번째 값을 읽을지** 정함. 발송 제한의 IP 기준도 함께 |
| | 구글 배포용 리디렉션 URI — **도메인 + HTTPS 가 있어야 등록 자체가 됨** |
| **AWS 배포 때** | RS256 키 새 쌍 + 공개키 `gateway-server-prod.yml` |
| | 구글 앱 게시 (Testing → Production) |
| | springdoc 끄기 |
| **S3 가 생기면** | 메일 상단에 로고 `<img>` 한 줄 |
| **user · pet 이 생기면** | Swagger 를 게이트웨이 뒤에 통합할지 판단 |
| **verdict · search 착수 시** | `RestClientAuthInterceptor` 배선 — auth 는 남을 안 불러 여기서 못 정함 |

---

**`ip_address` 가 NULL 인 이유**

게이트웨이가 `X-Forwarded-For` 를 넣지 않는 것이 실물로 확인됐고, `remoteAddr` 은 게이트웨이 IP(`127.0.0.1`)라
넣어도 쓸모가 없습니다. 진짜 IP 는 nginx 가 있어야 나오고 그때 *"신뢰하는 프록시가 몇 단인지"* 를 함께 정합니다.

> `server.forward-headers-strategy=framework` 를 켜면 `ForwardedHeaderTransformer` 와 충돌해
> `X-Forwarded-For` 만 안 실리는 사례(spring-cloud-gateway #2648)가 있습니다. 그때 기억할 것.

<br><br>

---

### 10-2. 판단만 남은 것 — 급하지 않음

| 무엇 | 상태 |
|---|---|
| `JwtEncoderConfig` 의 프로퍼티 등록을 `PropertiesConfig` 로 옮길지 | 지금 넷이라 급하지 않음. 늘면 어색해짐 |
| 발송 제한을 `INCR` 방식으로 바꿀지 | 더 나은 구현이고 Lua 도 필요 없음. 틀려서가 아니라 급하지 않아서 안 함 |
| `max.block.ms` 를 낮출지 | 카프카가 죽었을 때 3초가 아니라 60초를 붙잡음. 공통 모듈 문제 |
| 탈퇴 계정을 일정 기간 뒤 실제로 지우는 배치 | 지금 규모에서 불필요 |
| `state` 값에 `returnTo` 를 담을지 | "장소 상세에서 바로 로그인" 흐름이 생기면. **그때 열린 리다이렉트 검사를 함께** |

---

**의도적으로 안 한 것 — 다시 꺼내지 않음**

| 무엇 | 왜 |
|---|---|
| 탈퇴 직후 액세스 토큰 즉시 폐기 | 게이트웨이가 매 요청마다 폐기 목록을 조회해야 함 = 무상태 결정을 뒤집는 일. 30분 동안 조회만 가능 |
| `email_verified` 만으로 자동 연결하지 말라 | 회사 이메일 재할당 시나리오. 심사용 서비스에서 성립하지 않고 검증할 방법도 없음 |
| Redis 클러스터 해시 태그 | Redis 는 단일 인스턴스. 쓰지도 않을 구성을 위해 인터페이스를 넓히지 않음 |
| `@Version` 낙관적 잠금 | 탭 두 개 동시 갱신이 `OptimisticLockException` 으로 깨짐 — Lua 로 만든 "둘 다 200" 이 무너짐 |
| 통합 테스트 | auth 에 테스트가 `contextLoads` 하나뿐. 없는 체계를 이슈 안에서 세울 수 없음 |

<br><br>

---

### 10-3. 프론트에 전달할 것

| 무엇 | 왜 |
|---|---|
| 401 시 자동 `/refresh` 후 재시도하는 인터셉터 + 동시 401 큐잉 | 30분마다 로그인하지 않게 |
| 로그인 여부는 `GET /auth/me` 로만 | 쿠키가 HttpOnly |
| `nickname` 은 `GET /users/me` 로 | auth 응답에 없음 |
| 메일이 안 오면 스팸함 안내 | 개인 Gmail 발신 |
| 소셜 가입 직후 프로필 설정으로 유도 | `nickname` 이 null |
| `/login/success?isNew=` · `/login/error?reason=` 두 라우트 | 콜백이 302 로 보냄 |
| 탈퇴 확인 화면에 "삭제된 데이터는 복구할 수 없습니다" | 재가입은 새 계정 |
| 관리자 페이지 — outbox 5개 서비스를 각각 불러 한 화면에 | Swagger 로는 못 부름 |

<br><br>

---

## 11. 용어

공통 용어(트랜잭션 · 엔티티 · 컨테이너 등)는 `service-template` README 11장에 있습니다.
**여기는 인증에서만 쓰는 말**입니다.

| 용어 | 뜻 |
|---|---|
| **JWT** | 사용자 정보를 담고 서명한 문자열. `헤더.페이로드.서명`. 페이로드는 누구나 읽지만 고치면 서명이 안 맞음 |
| **claim** | JWT 페이로드 안의 항목 하나. `sub` · `role` · `exp` 등 |
| **`sub`** | subject. 토큰이 가리키는 사람. 우리는 계정 UUID |
| **`jti`** | JWT ID. 토큰 고유 번호. 리프레시 토큰의 Redis 키 |
| **`iat`** · **`exp`** | 발급 시각 · 만료 시각 (초 단위) |
| **`typ`** | 우리가 넣은 claim. `access` 또는 `refresh` |
| **액세스 토큰** | 30분짜리. 모든 요청에 실려 감. 게이트웨이가 검증 |
| **리프레시 토큰** | 14일짜리. 갱신·로그아웃에만 실려 감. Redis 에 저장 |
| **로테이션** | 리프레시 토큰을 쓸 때마다 새것으로 바꾸는 것 |
| **유예 (grace)** | 로테이션 직후 30초 동안 옛 토큰을 경합으로 봐 주는 창 |
| **복제 탐지** | 유예가 지난 옛 토큰이 또 오면 훔친 것으로 보고 전부 폐기 |
| **`tokens_valid_from`** | 이 시각 이전 토큰은 전부 무효. 지우지 않고 폐기하는 장치 |
| **RS256** | RSA 비대칭 서명. 개인키로 만들고 공개키로 확인 |
| **개인키 · 공개키** | 한 쌍. 개인키는 auth 만, 공개키는 게이트웨이 |
| **BCrypt** | 비밀번호 해시. 같은 입력도 매번 다른 결과이고 72바이트 상한 |
| **HttpOnly** | 자바스크립트가 못 읽는 쿠키 |
| **SameSite** | 다른 사이트에서 시작된 요청에 쿠키를 실을지. `Strict` 는 안 실음, `Lax` 는 최상위 GET 이동만 |
| **XSS** | 스크립트 주입. `localStorage` 의 토큰을 훔쳐 감 |
| **CSRF** | 다른 사이트에서 요청 위조. 쿠키가 자동으로 실리는 것을 이용. `SameSite` 로 막음 |
| **OAuth** | 구글 같은 제공자에게 로그인을 맡기는 규격 |
| **`code`** | 구글이 콜백에 붙여 주는 1회용 값. 이것으로 토큰을 교환 |
| **`id_token`** | 구글이 주는 JWT. 사용자 정보(`sub` · `email`)가 들어 있음 |
| **`sub` (구글)** | 구글 계정 고유 ID. `provider_user_id` 에 저장 |
| **`state`** | 우리가 시작한 로그인인지 확인하는 랜덤값 |
| **`nonce`** | `id_token` 재사용을 막는 랜덤값 |
| **JWKS** | 구글의 공개키 목록 주소. `id_token` 서명 검증에 씀 |
| **redirect URI** | 구글이 로그인 뒤 브라우저를 보낼 우리 주소. 콘솔에 등록한 것과 같아야 함 |
| **SMTP** | 메일 보내는 규약. Gmail 은 587 포트 + STARTTLS |
| **앱 비밀번호** | Gmail 이 SMTP 용으로 따로 발급하는 16자리. 계정 비밀번호로는 접속 불가 |
| **outbox** | 이벤트를 DB 에 먼저 저장하고 커밋 뒤 발행. `service-template` README 7-4 |
| **Lua 스크립트** | Redis 안에서 여러 명령을 한 덩어리로 실행. 그 사이에 다른 명령이 안 끼어듦 |
| **`SETNX` / `setIfAbsent`** | 키가 없을 때만 저장. 동시 요청 중 하나만 성공 |
| **`GETDEL`** | 읽고 바로 지움. 원자적 |
| **멱등** | 여러 번 해도 결과가 한 번과 같음. 로그아웃이 그래야 함 |
| **경합 (race)** | 두 요청이 같은 자리에 동시에 들어와 "확인과 실행 사이" 가 벌어지는 것 |
