# 함께하개 서비스 템플릿

이 문서는 서비스 레포를 새로 만든 뒤 무엇을 어떻게 세팅하고, 각 폴더와 파일이 무슨 일을 하는지를 정리한 개발 지침입니다.

**개발하는 동안 계속 참고합니다.** 구현이 끝나면 이 내용을 지우고 해당 서비스를 설명하는 README로 교체합니다.

파일 이름 뒤의 괄호는 자바 타입을 뜻합니다. 자바에서는 클래스, 인터페이스, enum, record가 모두 `.java` 파일이라 이름만으로는 구분되지 않으므로 따로 표기했습니다.

---

## 0. 이 문서를 읽는 순서

문서가 길지만 처음부터 끝까지 읽을 필요는 없습니다. 지금 무엇을 하려는지에 따라 볼 곳이 다릅니다.

| 지금 하려는 일 | 볼 곳 |
|---|---|
| 방금 레포를 만들었고 코드를 처음 연다 | **1장** 을 순서대로 따라 합니다. 여기만 끝내면 빌드가 통과합니다 |
| 로컬에서 서비스를 띄워보려 한다 | **2장**. 무엇을 Docker 로 띄우고 무엇을 IntelliJ 에서 실행하는지 나옵니다 |
| 공통 모듈이 무엇인지 모르겠다 | **6장**. 무엇이 들어 있고 어떻게 쓰는지 코드 예시와 함께 있습니다 |
| 코드를 어느 폴더에 둘지 모르겠다 | **5장**(왜 이렇게 나누는가) → **7장**(폴더마다 무엇을 두는가) |
| 기동이 안 되거나 IntelliJ 가 빨간 줄을 긋는다 | **1-7**, **10장** |
| DB 를 쓰지 않는 서비스를 맡았다 | **1-4** → **8장** |

**처음 읽는다면 1장 → 5장 → 6장 → 7장 순서를 권합니다.** 1장으로 환경을 만들고, 5장으로 왜 이렇게 나눴는지 이해한 뒤, 6장에서 공통으로 제공되는 것을 익히고, 7장에서 실제 파일을 어디에 만들지 확인하는 흐름입니다.

### 먼저 알아두면 좋은 것 3가지

**① 서비스마다 DB 가 따로 있고, 남의 DB 에는 접속할 수 없습니다.** PostgreSQL 인스턴스는 하나지만 그 안에 서비스별 DB 와 전용 계정이 나뉘어 있습니다. 다른 서비스의 데이터가 필요하면 **그 서비스의 API 를 호출하거나 이벤트를 받습니다.**

**② 인증은 게이트웨이가 끝냅니다.** 각 서비스는 JWT 를 직접 다루지 않습니다. 게이트웨이가 토큰을 검증한 뒤 `X-User-Id`·`X-User-Role` 헤더로 넣어주고, 공통 모듈의 필터가 그것을 읽어 `SecurityContext` 를 채웁니다. **게이트웨이는 헤더를 넣기 전에 바깥에서 들어온 같은 이름의 헤더를 먼저 지웁니다.** 그래야 이 헤더를 그대로 믿는 것이 성립합니다.

**③ 이벤트는 카프카로 바로 보내지 않습니다.** 자기 DB 의 `outbox` 테이블에 먼저 저장하고, 커밋된 뒤에 별도 스레드가 발행합니다. "데이터는 저장됐는데 이벤트는 안 나갔다"를 막기 위한 구조이며, 공통 모듈이 전부 처리하므로 사용하는 쪽은 한 줄만 부르면 됩니다.

---

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring Cloud | 2025.1.3 (Oakwood) |
| Gradle | 9.5.1 (래퍼로 고정) |
| PostgreSQL | 17 + PostGIS 3.5 |
| QueryDSL | io.github.openfeign.querydsl 7.6 |
| springdoc-openapi | 3.1.0 |

Spring Boot 3.x 계열은 2026년 6월 30일에 오픈소스 지원이 끝나 사용하지 않습니다.

QueryDSL은 본가(`com.querydsl`)가 아니라 OpenFeign 포크를 사용합니다. **groupId만 다르고 패키지명은 `com.querydsl` 그대로**이므로 소스 코드는 일반적인 QueryDSL 예제와 동일하게 작성합니다.

---

## 1. 복제 후 최초 설정

TEMPLATE_REPO에서 "Use this template"으로 새 레포를 만든 뒤 한 번만 수행하는 절차입니다.

### 1-1. 패키지 경로 치환은 IntelliJ를 열기 전에

템플릿의 패키지 경로는 `com.pawtrail.template`입니다. 이것을 서비스명으로 바꿔야 하는데, IntelliJ로 프로젝트를 연 뒤에 바꾸면 프로젝트 구조 정보가 이전 이름으로 남아 모듈을 다시 인식시키는 작업이 따라붙습니다. **clone 직후 터미널에서 먼저 처리한 다음 IntelliJ를 여는 것이 가장 빠릅니다.**

아래는 place 서비스를 만드는 예시입니다. 맨 위 값 두 개만 자기 도메인에 맞게 바꾸고, **레포 루트에서** 나머지를 그대로 실행합니다.

운영체제마다 명령어가 다르므로 해당하는 것 하나만 실행합니다.

#### macOS

터미널에서 실행합니다. macOS에 기본 설치된 sed는 `-i` 뒤에 빈 인자를 하나 더 요구하므로 `sed -i ''` 형태입니다.

```bash
NEW=place        # 소문자 서비스명
CLASS=Place      # 첫 글자만 대문자로 바꾼 것

# 1) 패키지 폴더 이름 변경 (main, test 양쪽)
mv src/main/java/com/pawtrail/template src/main/java/com/pawtrail/$NEW
mv src/test/java/com/pawtrail/template src/test/java/com/pawtrail/$NEW

# 2) 소스 안의 package 선언과 import 치환
grep -rl "com.pawtrail.template" src | xargs sed -i '' "s/com\.pawtrail\.template/com.pawtrail.$NEW/g"

# 3) 클래스 파일 이름 변경 (main, test 양쪽)
mv src/main/java/com/pawtrail/$NEW/TemplateApplication.java \
   src/main/java/com/pawtrail/$NEW/${CLASS}Application.java
mv src/test/java/com/pawtrail/$NEW/TemplateApplicationTests.java \
   src/test/java/com/pawtrail/$NEW/${CLASS}ApplicationTests.java

# 4) 소스 안의 클래스명 치환
grep -rl "TemplateApplication" src | xargs sed -i '' "s/TemplateApplication/${CLASS}Application/g"
```

#### Windows — Git Bash

Git for Windows를 설치하면 함께 들어오는 Git Bash에서 실행합니다. **Windows에서는 이 방법을 권장합니다.** 명령어는 macOS와 같고 `sed -i` 뒤에 빈 인자만 없습니다.

```bash
NEW=place        # 소문자 서비스명
CLASS=Place      # 첫 글자만 대문자로 바꾼 것

# 1) 패키지 폴더 이름 변경 (main, test 양쪽)
mv src/main/java/com/pawtrail/template src/main/java/com/pawtrail/$NEW
mv src/test/java/com/pawtrail/template src/test/java/com/pawtrail/$NEW

# 2) 소스 안의 package 선언과 import 치환
grep -rl "com.pawtrail.template" src | xargs sed -i "s/com\.pawtrail\.template/com.pawtrail.$NEW/g"

# 3) 클래스 파일 이름 변경 (main, test 양쪽)
mv src/main/java/com/pawtrail/$NEW/TemplateApplication.java \
   src/main/java/com/pawtrail/$NEW/${CLASS}Application.java
mv src/test/java/com/pawtrail/$NEW/TemplateApplicationTests.java \
   src/test/java/com/pawtrail/$NEW/${CLASS}ApplicationTests.java

# 4) 소스 안의 클래스명 치환
grep -rl "TemplateApplication" src | xargs sed -i "s/TemplateApplication/${CLASS}Application/g"
```

#### Windows — PowerShell

Git Bash를 쓸 수 없을 때만 사용합니다. 파일을 다시 쓰는 과정에서 인코딩이 바뀌면 한글 주석이 깨질 수 있으므로 **PowerShell 7 이상**에서 실행합니다.

```powershell
$NEW   = "place"    # 소문자 서비스명
$CLASS = "Place"    # 첫 글자만 대문자로 바꾼 것

# 1) 패키지 폴더 이름 변경 (main, test 양쪽)
Rename-Item "src\main\java\com\pawtrail\template" $NEW
Rename-Item "src\test\java\com\pawtrail\template" $NEW

# 2) 소스 안의 package 선언과 import 치환
Get-ChildItem -Path src -Recurse -File | ForEach-Object {
    (Get-Content $_.FullName -Raw) `
        -replace "com\.pawtrail\.template", "com.pawtrail.$NEW" `
        -replace "TemplateApplication", "${CLASS}Application" |
        Set-Content $_.FullName -NoNewline -Encoding utf8
}

# 3) 클래스 파일 이름 변경 (main, test 양쪽)
Rename-Item "src\main\java\com\pawtrail\$NEW\TemplateApplication.java" "${CLASS}Application.java"
Rename-Item "src\test\java\com\pawtrail\$NEW\TemplateApplicationTests.java" "${CLASS}ApplicationTests.java"
```

#### 제대로 바뀌었는지 확인

아래 명령이 **아무것도 출력하지 않으면** 성공입니다. 무언가 출력된다면 그 파일에 옛 이름이 남아 있는 것이므로 직접 고칩니다.

```bash
# macOS / Git Bash
grep -r "com.pawtrail.template\|TemplateApplication" src
```

```powershell
# PowerShell
Select-String -Path src\*.* -Pattern "com\.pawtrail\.template|TemplateApplication" -Recurse
```

**파일 이름도 함께 확인합니다.** 위 명령은 파일 *내용*만 검사합니다. 클래스 이름은 바뀌었는데 파일 이름이 그대로 남아 있으면, 테스트 클래스는 `public` 이 아니라 컴파일은 통과하고 이름만 어긋난 채 남습니다.

```bash
# main, test 양쪽에 Template 이 들어간 파일이 없어야 합니다
find src -name "Template*"
```

### 1-2. 이미 IntelliJ로 연 뒤에 바꿨다면

프로젝트 정보가 이전 이름으로 캐시되어 있어 모듈을 제대로 인식하지 못합니다. IntelliJ를 닫고 프로젝트 루트의 `.idea` 폴더와 `*.iml` 파일을 지운 다음 다시 열면 처음부터 다시 인식합니다. 소스 코드에는 영향이 없습니다.

### 1-3. 직접 고쳐야 하는 파일

치환 스크립트로 처리되지 않는 파일들입니다. 아래 예시는 모두 `place-service` 를 만드는 경우입니다.

**먼저 이름이 두 종류라는 것을 알아 둡니다.** 자바 패키지에는 하이픈을 쓸 수 없어 `com.pawtrail.place-service` 가 불가능하므로, 자리에 따라 값이 갈립니다.

| 자리 | 값 | 예시 |
|---|---|---|
| 저장소명 | 전체 이름 | `place-service` |
| 이미지명 (`Jenkinsfile` 의 `serviceName`) | 전체 이름 | `place-service` |
| `spring.application.name` | 전체 이름 | `place-service` |
| `config` 저장소의 파일명 | 전체 이름 | `place-service.yml` |
| 유레카 등록 이름 | 전체 이름 | `place-service` |
| `settings.gradle` 의 `rootProject.name` | 전체 이름 | `place-service` |
| 자바 패키지 | **접미사를 뗀 이름** | `com.pawtrail.place` |
| 앱 클래스 | **접미사를 뗀 이름** | `PlaceApplication` |

아래 여섯 줄은 전부 **저장소 바깥과 맺는 계약**이라 한 글자라도 어긋나면 다른 시스템이 이 서비스를 찾지 못합니다. 이미지 태그, 설정 파일 조회, 유레카 등록과 게이트웨이의 `lb://`, Loki 라벨과 Zipkin 서비스 이름이 모두 이 문자열에 걸려 있습니다.

반대로 자바 패키지는 **이 저장소 안에서만 쓰이므로** 배포에 관여하지 않습니다. 규칙은 이렇습니다.

> 패키지와 클래스명은 저장소명에서 `-service` 접미사를 떼고 하이픈을 지운 것입니다.

`-server` 는 떼지 않습니다. `gateway-server` 는 그 물건의 이름 자체이므로 `com.pawtrail.gatewayserver` 가 되고, `-service` 는 "도메인 서비스"라는 분류 꼬리표라 패키지 안에서는 의미가 없습니다.

#### settings.gradle

프로젝트 이름을 바꿉니다. 이 값이 빌드 산출물 jar 이름이 되므로 Dockerfile과도 연결됩니다. **저장소명을 그대로 씁니다.**

```groovy
// 바꾸기 전
rootProject.name = 'template'

// 바꾸기 후
rootProject.name = 'place-service'
```

#### gradle.properties

공통 모듈 버전을 최신으로 맞춥니다. **템플릿에 적힌 값이 최신이 아닐 수 있으므로** 조직 Packages 페이지에서 확인한 뒤 다르면 고칩니다.

```properties
commonVersion=0.0.4
```

이 값만 바꾸고 다시 빌드하면 새 버전이 내려옵니다. 올리는 절차와 주의할 점은 3-3에 있습니다.

#### src/main/resources/application.yml

**이 파일에서 고칠 것은 서비스 이름 한 줄뿐입니다.** 포트·데이터베이스·주소는 모두 `paw-trail/config` 저장소에 있으며 설정 서버가 내려줍니다.

```yaml
# 바꾸기 전
spring:
  application:
    name: template-service
  config:
    import: "optional:configserver:http://${CONFIG_HOST:localhost}:8888"
  profiles:
    default: local
```

```yaml
# 바꾸기 후
spring:
  application:
    name: place-service
  config:
    import: "optional:configserver:http://${CONFIG_HOST:localhost}:8888"
  profiles:
    default: local
```

세 줄의 뜻은 각각 이렇습니다.

| 키 | 하는 일 |
|---|---|
| `spring.application.name` | 설정 서버에서 **이 이름의 파일을 찾습니다.** 저장소명·이미지명·유레카 등록 이름과도 같아야 합니다 |
| `spring.config.import` | 설정을 받아 올 주소입니다. **`optional:` 이 붙어 있어 설정 서버가 없어도 기동됩니다** |
| `spring.profiles.default` | 아무도 프로파일을 정해 주지 않으면 `local` 로 봅니다. `active` 가 아니라 `default` 인 것이 중요하며, 컨테이너의 `SPRING_PROFILES_ACTIVE=dev` 가 이깁니다 |

`optional:` 을 붙이는 이유는 서비스 하나만 띄워 확인하는 일이 잦기 때문입니다. 이것이 없으면 매번 설정 서버를 함께 띄워야 하고 테스트도 실패합니다. 다만 **데이터베이스 주소가 내려오지 않으므로 실제 기동은 설정 서버가 떠 있어야 합니다.**

`${CONFIG_HOST:localhost}` 에 기본값을 붙이는 것은 의도입니다. 로컬에서는 언제나 `localhost:8888` 이므로 기본값이 정답이고, 없으면 개발자마다 실행 구성에 환경 변수를 넣어야 합니다.

#### config 저장소에 이 서비스의 설정 파일 만들기

**복제 직후 반드시 해야 하는 작업입니다.** 이 파일이 없으면 포트와 데이터베이스 주소가 내려오지 않아 기동에 실패합니다.

`paw-trail/config` 저장소 루트에 `<서비스명>.yml` 을 만듭니다.

```yaml
# =============================================================================
# 2계층 — place-service
# =============================================================================
# 장소 담당임
#
# 호스트는 3계층의 app.datasource.host 에서 오고 비밀번호는 1계층에 있음
# 계정 10개가 같은 비밀번호를 쓰므로 여기에는 계정명만 둠
# =============================================================================

server:
  port: 8084

spring:
  datasource:
    url: jdbc:postgresql://${app.datasource.host}:5432/place_db
    username: place_svc

app:
  outbox:
    relay:
      # place.updated 를 발행함
      enabled: true
```

- **포트**는 2-3절의 배정표를 따릅니다
- **데이터베이스 계정**은 `<서비스>_svc` 형식입니다. `<서비스>_user` 가 아닙니다
- **`app.outbox.relay.enabled`** 는 이벤트를 발행하는 서비스에서만 `true` 로 둡니다. 인스턴스를 여러 개 띄우는 서비스라면 한 인스턴스에서만 켭니다
- **`app.auditor.system-name`** 은 1계층에 `SYSTEM` 으로 있으므로 배치가 아니면 적지 않습니다. `ingest` 와 `extract` 만 각각 `ingest-batch`, `extract-batch` 로 덮습니다
- 데이터베이스를 쓰지 않는 서비스는 `server.port` 만 적습니다

설정 계층과 값을 어디에 둘지는 4장에, `config` 저장소 자체의 규칙은 그 저장소의 README에 있습니다.

#### 게이트웨이에 이 서비스의 라우트 열기

**바로 위 작업과 짝입니다.** 설정 파일만 만들면 서비스는 정상으로 뜨고 유레카에도 등록되지만, **브라우저에서 이 서비스의 API 를 부를 수 없습니다.**

```
서비스는 UP · 유레카 대시보드에도 보임
브라우저 → 게이트웨이 → 404 ROUTE_NOT_FOUND
```

게이트웨이는 라우트 목록에 없는 경로를 그냥 404로 돌려보냅니다. **이 서비스의 로그에는 아무것도 남지 않으므로** 서비스를 아무리 들여다봐도 원인이 드러나지 않습니다.

`paw-trail/config` 저장소의 `gateway-server.yml` 에서 `routes` 아래에 한 덩어리를 추가합니다.

```yaml
            - id: place-service
              uri: lb://place-service
              predicates:
                - Path=/api/v1/places/{placeId},/api/v1/places/{placeId}/documents
```

| 항목 | 규칙 |
|---|---|
| `id` | 자유롭게 정하지만 서비스명과 같게 둡니다 |
| `uri` | `lb://` 뒤의 이름이 **그 서비스의 `spring.application.name` 과 같아야 합니다.** 다르면 유레카에서 주소를 찾지 못해 503이 납니다 |
| `predicates` | 이 서비스로 보낼 경로입니다. 쉼표로 여러 개를 적을 수 있습니다 |

한 서비스가 접두사를 여러 개 가지는 경우도 있습니다. 여러 리소스를 한 서비스가 소유하기 때문입니다.

```yaml
            - id: user-service
              uri: lb://user-service
              predicates:
                - Path=/api/v1/users/**,/api/v1/favorites/**,/api/v1/visits/**,/api/v1/itineraries/**

            - id: pet-service
              uri: lb://pet-service
              predicates:
                - Path=/api/v1/pets/**,/api/v1/breeds
```

**`/api/v1/places/` 아래에는 `/**` 를 쓰지 않습니다.**

이 접두사 아래에는 서비스 6개가 섞여 있습니다. 장소 상세 화면에서 브라우저가 여러 개를 한꺼번에 부르기 때문이며, 경로는 장소를 중심으로 짜여 있고 소유 서비스는 갈려 있습니다.

| 경로 | 가는 곳 |
|---|---|
| `/api/v1/places/{placeId}` | place |
| `/api/v1/places/{placeId}/documents` | place |
| `/api/v1/places/{placeId}/verdict` | verdict |
| `/api/v1/places/{placeId}/reviews` | review |
| `/api/v1/places/{placeId}/conflicts` | policy |
| `/api/v1/places/{placeId}/congestion` | congestion |

여기에 `Path=/api/v1/places/**` 를 쓰면 **하위 경로를 모두 먹어 나머지 다섯으로 갈 요청이 전부 첫 라우트로 갑니다.** 게이트웨이는 처음 맞는 라우트에서 멈추기 때문입니다. 증상은 "장소 상세에서 판정만 안 뜬다" 인데 게이트웨이 로그에는 아무것도 남지 않습니다.

`{placeId}` 는 **한 마디만 맞추므로** 여섯이 서로 겹치지 않고, 그래서 목록에 적는 순서를 신경 쓰지 않아도 됩니다.

**place 에 하위 경로가 새로 생기면 라우트도 함께 추가합니다.** 추가하지 않으면 404입니다.

**관리자 경로는 따로 적습니다.**

**두 번째 마디가 어느 서비스인지를 정합니다.** 예외 없이 이 규칙을 지키므로 새 관리자 경로를 만들 때도 같은 모양으로 둡니다.

```yaml
            - id: admin-places
              uri: lb://place-service
              predicates:
                - Path=/api/v1/admin/places/**
```

이벤트를 발행하는 서비스라면 이 라우트 아래에 **Outbox 재발행 API** 도 함께 들어갑니다. 7장의 「이벤트를 발행하는 서비스는 관리자 재발행 API 를 만듭니다」를 참고합니다.

**인증 없이 열어야 하는 경로가 있다면** 같은 파일의 `app.gateway.permit-all` 에도 추가합니다. 여기 없는 경로는 전부 토큰을 확인합니다.

```yaml
app:
  gateway:
    permit-all:
      - /api/v1/auth/login
```

**이 목록에 넣은 경로에는 게이트웨이가 `X-User-Id` 를 넣어주지 않습니다.** 그 서비스가 자기 보안 설정을 따로 정의한다면 **그쪽에서도 같은 경로를 열어야 하며, 한쪽만 열면 401이 납니다.** 같은 목록이 두 곳에 존재하는 셈이므로, 고칠 때는 양쪽을 함께 봅니다.

**확인은 실제로 도는 목록으로 합니다.** 게이트웨이를 다시 띄우거나 `POST /actuator/refresh` 를 부른 뒤 아래를 봅니다.

```powershell
curl.exe http://localhost:8080/actuator/gateway/routes
```

내가 쓴 것과 실제로 도는 것이 다를 수 있으므로, 어긋나 보이면 설정 서버가 내려주는 값도 함께 대조합니다.

```powershell
curl.exe http://localhost:8888/gateway-server/local
```

#### Dockerfile



jar 경로가 `settings.gradle`의 이름을 따라갑니다. 아래처럼 와일드카드로 되어 있다면 고치지 않아도 됩니다.

```dockerfile
# 이 형태라면 그대로 둡니다
COPY build/libs/*.jar app.jar

# 이름이 박혀 있다면 바꿉니다
# 바꾸기 전
COPY build/libs/template-0.0.1-SNAPSHOT.jar app.jar
# 바꾸기 후
COPY build/libs/place-service-0.0.1-SNAPSHOT.jar app.jar
```

**와일드카드가 안전한 것은 `build/libs` 에 jar가 하나만 생기기 때문입니다.** `build.gradle` 맨 아래에서 `jar` 태스크를 꺼 두었습니다.

```gradle
tasks.named('jar') {
    enabled = false
}
```

이 줄이 없으면 실행 가능한 jar와 함께 클래스만 든 `-plain.jar` 가 만들어집니다. 그러면 와일드카드가 **둘 다 잡는데, 복사 대상이 파일 하나라 어느 쪽이 담길지는 빌드 도구의 파일 정렬 순서에 달립니다.**

잘못 담겨도 **이미지는 정상으로 만들어지고 기동할 때만 실패합니다.**

```
no main manifest attribute, in app.jar
```

지금은 우연히 실행 가능한 쪽이 담기지만 이름이 조금만 달라져도 뒤집히므로, **저 줄을 되살리지 않습니다.**

만든 이미지를 확인하려면 아래처럼 봅니다. 실행 가능한 jar는 수십 MB이고 `-plain.jar` 는 몇 KB입니다.

```powershell
docker run --rm --entrypoint sh <이미지> -c "ls -lh /app"
```

`--entrypoint` 를 빼면 뒤에 쓴 명령이 대체가 아니라 인자로 붙어 애플리케이션이 그냥 뜹니다.

**공통 모듈은 반대입니다.** 그쪽은 실행되는 앱이 아니라 다른 프로젝트가 의존성으로 쓰는 라이브러리라 `bootJar` 를 끄고 `jar` 를 켭니다.

#### Jenkinsfile

파이프라인 본체는 공유 라이브러리에 있으므로 파라미터 3개만 채웁니다. `deployNode`는 이 서비스가 올라갈 노드이고, `instances`는 띄울 개수입니다. 어느 노드에 몇 개인지는 9장의 분류표와 인프라 문서를 따릅니다.

**`serviceName` 은 저장소명을 그대로 씁니다.** 이 값이 그대로 이미지 태그가 되기 때문입니다. 짧게 적으면 Jenkins 는 `ghcr.io/paw-trail/place` 로 올리는데 배포는 `ghcr.io/paw-trail/place-service` 를 내려받으려 하므로 `manifest unknown` 으로 실패합니다. **배포 시점에야 드러나고 메시지가 이름 문제라는 것을 알려주지 않습니다.**

```groovy
// 바꾸기 전
@Library('pawtrail-pipeline') _
springServicePipeline(
    serviceName: 'template',
    deployNode : 'app',
    instances  : 1
)

// 바꾸기 후
@Library('pawtrail-pipeline') _
springServicePipeline(
    serviceName: 'place-service',
    deployNode : 'core',
    instances  : 1
)
```

#### src/main/resources/db/migration/

템플릿에 들어 있는 예시 스크립트를 지우고, 이 서비스의 첫 스크립트를 만듭니다. 파일명은 `V20__<서비스명>.sql`입니다.

```
바꾸기 전   db/migration/service/V20__template.sql   (예시 내용)
바꾸기 후   db/migration/service/V20__place.sql      (이 서비스의 테이블 정의)
```

```sql
-- V20__place.sql 예시
CREATE TABLE place (
    id              uuid         PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    -- ... 이 서비스의 컬럼 정의
    created_at      TIMESTAMP    NOT NULL,
    created_by      VARCHAR(45)  NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    updated_by      VARCHAR(45)  NOT NULL,
    deleted_at      TIMESTAMP,
    deleted_by      VARCHAR(45)
);
```

**PK는 모든 테이블이 `uuid`입니다.** DB 함수로 만들지 않고 애플리케이션이 `@UuidGenerator(style = UuidGenerator.Style.VERSION_7)` 로 생성해 넣으므로, 스크립트에는 기본값을 지정하지 않습니다. 순차 숫자를 쓰지 않는 이유는 서비스가 여러 개라 `place_id=42` 와 `pet_id=42` 가 구분되지 않고, 순차 ID가 URL에 노출되면 데이터 규모가 드러나기 때문입니다.

마지막 6개 컬럼은 모든 테이블이 공통으로 갖습니다. `BaseEntity`가 이 컬럼들과 짝을 이루므로 빠뜨리면 기동 시 검증에 실패합니다. `created_*` 와 `updated_*` 는 JPA Auditing 이 항상 채우므로 `NOT NULL` 이고, `deleted_*` 는 소프트 딜리트 시점에만 채워지므로 NULL 을 허용합니다.

#### README.md

**지금은 고치지 않습니다.** 이 파일에는 지금 읽고 있는 개발 지침이 들어 있으며, 개발하는 동안 계속 참고하게 됩니다.

구현이 끝난 뒤에 이 지침 내용을 전부 지우고, 해당 서비스에 맞는 README로 새로 작성합니다. 참고용 최종 형태는 다음과 같습니다.

```markdown
# place

## 역할
장소 마스터 데이터를 소유하고 조회를 제공합니다.

## 소유 데이터
place_db — place, place_source_link, place_facility

## 의존
- 호출하는 서비스: ingest (/internal/raw)
- 구독하는 이벤트: 없음
- 발행하는 이벤트: place.updated

## 로컬 실행
infra 저장소에서 docker compose up -d
이후 PlaceApplication 을 IntelliJ에서 실행합니다.
```

의존 관계 항목은 나중에 서비스 간 호출 관계를 파악하는 근거가 되므로 반드시 채웁니다. 서비스가 여러 개이므로 이 항목만 모아 읽으면 전체 호출 관계가 드러납니다.

### 1-4. DB를 사용하지 않는 서비스라면

verdict, congestion, route 처럼 DB 가 없는 서비스는 **네 군데를 지워야 합니다.** 한 곳만 고치면 컴파일이나 기동이 실패하므로 아래 순서대로 함께 처리합니다.

**`extract` 는 이 절을 그대로 따르지 않습니다.** 소유 DB 는 없지만 Spring Batch 가 실행 이력 테이블을 요구하므로, 어디에 둘지 정해진 뒤에 판단합니다. 9장을 먼저 봅니다.

> **왜 지워야 하는가** — 공통 모듈은 클래스패스에 `spring-data-jpa` 가 있으면 JPA 관련 설정을 자동으로 켭니다. DB 가 없는 서비스에서 켜지면 `entityManagerFactory` Bean 을 찾지 못해 기동에 실패합니다. 의존성을 지우면 자동 설정도 함께 꺼집니다.

#### ① `build.gradle` — 데이터·QueryDSL 블록을 통째로

`── 데이터 ──` 주석부터 QueryDSL 블록 끝까지가 대상입니다. **JPA 4줄만 지우면 안 됩니다.** `hibernate-spatial` 과 QueryDSL 이 `hibernate-core` 와 `jakarta.persistence-api` 를 클래스패스에 남겨, 쓰지도 않는 의존성이 이미지에 실리고 애노테이션 프로세서도 계속 돕니다.

```groovy
    // ── 웹 · 검증 · 보안 · 상태확인 ────────────────────────────
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // ▼▼▼ 여기부터 지웁니다 ▼▼▼
    // ── 데이터 ────────────────────────────────────────────────
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly   'org.postgresql:postgresql'

    implementation 'org.hibernate.orm:hibernate-spatial'

    // ── QueryDSL ──────────────────────────────────────────────
    implementation      "io.github.openfeign.querydsl:querydsl-jpa:${querydslVersion}"
    annotationProcessor "io.github.openfeign.querydsl:querydsl-apt:${querydslVersion}:jpa"
    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
    // ▲▲▲ 여기까지 지웁니다 ▲▲▲

    // ── 캐시 · 이벤트 ─────────────────────────────────────────
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-kafka'
```

테스트 블록 맨 위의 두 줄도 함께 지웁니다. 여기만 남으면 테스트 클래스패스에만 JPA 가 살아남아, 나중에 테스트가 왜 다르게 도는지 찾기 어려워집니다.

```groovy
    // ── 테스트 ────────────────────────────────────────────────
    // ▼▼▼ 이 두 줄을 지웁니다 ▼▼▼
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-flyway-test'
    // ▲▲▲ 여기까지 ▲▲▲

    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
```

`spring-data-commons` 는 **지우지 않습니다.** 공통 모듈의 `PageResponse` 가 쓰는 것이고, DB 가 없는 서비스도 목록 응답을 반환합니다.

#### ② `<서비스명>Application.java` — 애노테이션 2줄과 import 2줄

**애노테이션만 지우고 import 를 남기면 컴파일이 실패합니다.** `package org.springframework.data.jpa.repository.config does not exist` 오류가 나는데, 원인이 애노테이션 쪽이라고 생각하기 쉬워 시간을 씁니다.

```java
package com.pawtrail.verdict;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;              // ← 지웁니다
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;       // ← 지웁니다

/**
 * ... 주석의 JPA 관련 문단도 함께 정리합니다 ...
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.pawtrail.verdict", "com.pawtrail.common"})         // ← 지웁니다
@EnableJpaRepositories(basePackages = {"com.pawtrail.verdict", "com.pawtrail.common"})  // ← 지웁니다
public class VerdictApplication {
```

지우고 나면 이 형태만 남습니다.

```java
@SpringBootApplication
public class VerdictApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerdictApplication.class, args);
    }
}
```

#### ③ config 저장소의 `<서비스명>.yml` — datasource 를 적지 않습니다

서비스 저장소의 `application.yml` 에는 세 줄뿐이므로 지울 것이 없습니다. 대신 **config 저장소에 만드는 2계층 파일에 `spring.datasource` 를 넣지 않습니다.**

```yaml
# 2계층 — verdict-service
# DB 를 쓰지 않으므로 datasource 를 두지 않음
server:
  port: 8086
```

1계층의 JPA·Flyway 값은 그대로 내려오지만, **JDBC 의존성 자체를 걷어낸 서비스라 아무 일도 일어나지 않습니다.** 관련 자동 설정이 클래스가 없어 아예 올라오지 않기 때문입니다.

`app.outbox.relay.enabled` 도 적지 않습니다. outbox 테이블이 없으므로 회수 스케줄러가 돌 대상이 없습니다. `app.auditor.system-name` 은 1계층에 `SYSTEM` 으로 있으므로 어차피 적을 일이 없습니다.

#### ④ 폴더 2개

```
src/main/resources/db/migration/service/  ← 폴더째 지웁니다 (테이블이 없습니다)
src/main/java/.../infrastructure/persistence/   ← 폴더째 지웁니다 (저장소가 없습니다)
```

#### 다 지웠는지 확인

아래가 **아무것도 출력하지 않으면** 성공입니다.

```bash
# macOS / Git Bash
grep -rn "jpa\|Jpa\|flyway\|Flyway\|querydsl\|QueryDsl\|datasource" build.gradle src/main/java
```

```powershell
# PowerShell
Select-String -Path build.gradle -Pattern "jpa|flyway|querydsl|datasource"
```

`application.yml` 은 세 줄뿐이므로 검사 대상에 넣지 않습니다.

그다음 빌드가 통과하는지 봅니다.

```bash
./gradlew compileJava
```

#### 무상태 서비스가 못 쓰게 되는 것

의존성을 지우면 공통 모듈에서 아래가 함께 꺼집니다. 대신 쓸 수 없게 되는 것이므로 알고 있어야 합니다.

| 못 쓰게 되는 것 | 대신 |
|---|---|
| `BaseEntity`, JPA Auditing | 엔티티가 없으므로 필요 없습니다 |
| `OutboxEventRecorder` (이벤트 발행) | 무상태 서비스는 이벤트를 발행하지 않습니다 |
| `InboxProcessor` (중복 처리 방지) | 8장 마지막 절을 참고합니다. 이 서비스들이 이벤트로 하는 일은 캐시 삭제뿐이라 중복 처리가 문제되지 않습니다 |

`CommonApiResponse`·`ErrorCode`·`@CurrentUser`·보안 필터는 **그대로 쓸 수 있습니다.** JPA 와 무관한 자동 설정이라 계속 켜집니다.

### 1-5. 공통 모듈은 자동 설정으로 등록됩니다

공통 모듈에는 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일이 있어 설정 클래스들이 자동으로 등록됩니다. 이 파일은 **공통 모듈 레포에만 존재하며, 서비스 레포에서는 만들지도 고치지도 않습니다.**

서비스 쪽에서 **따로 해야 할 일은 없습니다.** 의존성만 추가하면 조건에 맞는 Bean 이 알아서 올라옵니다.

#### `scanBasePackages` 에 `com.pawtrail.common` 을 넣지 않습니다

**넣으면 안 됩니다.** 자동 설정과 컴포넌트 스캔 양쪽에 걸리면 같은 설정이 두 번 등록되고, 조건 평가 순서가 깨져 의도와 다른 Bean 이 올라갈 수 있습니다. 스프링 부트도 자동 설정 클래스가 컴포넌트 스캔 대상이 되면 안 된다고 명시하고 있습니다.

템플릿의 `<서비스명>Application.java` 에는 `scanBasePackages` 자체가 없습니다. `@SpringBootApplication` 의 기본 스캔 범위가 그 클래스가 속한 패키지이므로 지정할 필요가 없고, 복제 후 고칠 문자열도 하나 줄어듭니다.

#### 반면 `@EntityScan` 과 `@EnableJpaRepositories` 에는 넣습니다

공통 모듈의 `OutboxMessage`·`ProcessedEvent` 엔티티와 그 레포지터리는 **자동 설정이 잡아주지 않습니다.** 이 둘에는 `com.pawtrail.common` 을 그대로 지정해야 하며, 템플릿에 이미 들어 있습니다.

세 애노테이션이 비슷하게 생겼지만 공통 모듈이 들어가는 곳은 뒤의 둘뿐입니다. 헷갈리기 쉬운 자리라 앱 클래스 주석에도 같은 설명이 있습니다.

### 1-6. 마지막으로 빌드가 되는지 확인합니다

```bash
# macOS / Git Bash
./gradlew compileJava

# Windows PowerShell — 앞의 .\ 를 빠뜨리면 명령을 찾지 못합니다
.\gradlew.bat compileJava
```

여기서 `compileJava` 만 하는 것은 컴파일만 확인하면 되기 때문입니다. `build` 를 돌리면 테스트가 함께 도는데, 그때 **PostgreSQL 컨테이너가 하나 떴다가 사라집니다.** 놀라지 않아도 되며 자세한 내용은 2-7에 있습니다.

**`BUILD SUCCESSFUL` 만 보고 넘어가지 않습니다.** 그 아래 `actionable tasks: N executed` 줄을 함께 확인합니다. 소스가 컴파일 대상에 잡히지 않으면 Gradle은 아무 일도 하지 않고 성공으로 끝나기 때문입니다. 패키지 경로 치환이 잘못되었을 때 이 형태로 나타납니다.

#### 터미널에서 실행할 때 JAVA_HOME 주의

**터미널의 `gradlew` 는 IntelliJ 설정의 Gradle JVM 을 보지 않고 `JAVA_HOME` 환경변수를 봅니다.** 둘은 별개이므로, IntelliJ 설정이 21로 되어 있어도 터미널에서는 다음과 같은 오류가 날 수 있습니다.

```
Gradle requires JVM 17 or later to run. Your build is currently configured to use JVM 11.
```

이때는 `JAVA_HOME` 을 JDK 21 경로로 맞춥니다. 설치 경로는 JDK 배포판마다 다르므로 직접 확인합니다.

```powershell
# 설치된 21 찾기
Get-ChildItem "C:\Program Files\*\*" -Directory | Where-Object { $_.Name -like "*21*" } | Select-Object FullName

# 이 터미널에서만 지정 (시스템 설정을 건드리지 않습니다)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
```

시스템 환경변수를 직접 바꿨다면 **터미널 창을 새로 열어야** 반영됩니다.

### 1-7. 처음 한 번 해두는 설정과 자주 겪는 것들

서비스를 만들 때마다 반복해서 겪게 되므로 미리 읽어 둡니다.

#### IntelliJ 가 Gradle 프로젝트로 인식하지 못할 때

폴더를 그냥 `Open` 으로 열면 "Load Gradle Project" 알림이 잠깐 떴다 사라지고 **평범한 디렉터리 프로젝트로 열립니다.** 아래 세 가지가 그 지문입니다.

| 지문 | 정상일 때 |
|---|---|
| `com`·`pawtrail`·`<서비스명>` 이 폴더 여러 개로 나뉘어 보임 | `com.pawtrail.<서비스명>` 한 줄로 접힙니다 |
| 트리 맨 아래에 `External Libraries` 노드가 없음 | 의존성 노드가 있습니다 |
| 실행 구성이 `Current File` 이고 main 옆에 실행 표시가 없음 | 초록 실행 표시가 있습니다 |

해결은 아래 순서로 시도합니다.

```
① build.gradle 우클릭 → Link Gradle Project
② Gradle 툴 창의 + 버튼으로 build.gradle 지정
③ File → Close Project 후 File → Open 에서
   ★폴더가 아니라 build.gradle 파일 자체를 선택 → Open as Project
```

③이 가장 확실합니다. 그래도 안 되면 `.idea` 폴더와 `*.iml` 을 지우고 ③을 반복합니다.

붙은 뒤 **Settings → Build Tools → Gradle 의 `Gradle JVM` 이 21인지 확인합니다.** 터미널의 `JAVA_HOME` 과 별개이므로 `gradlew` 가 잘 돌았다고 IntelliJ 도 되는 것이 아닙니다.

#### `bootRun` 의 진행률이 멈춘 것처럼 보입니다

`bootRun` 은 앱이 살아 있는 동안 끝나지 않는 태스크입니다. Gradle 진행 막대가 80% 근처에서 완료로 가지 않고 경과 시간만 올라가는데, **막대가 그 자리에 있다는 것이 곧 앱이 떠 있다는 표시입니다.** 앱을 멈춰야 100% 가 됩니다.

진행 막대 바로 아래 줄이 `> :bootRun` 이면 실행 중이고, `Resolve dependencies of ...` 나 `Download https://...` 면 의존성을 받는 중입니다.

**IntelliJ 의 Run 버튼으로 띄우는 편이 낫습니다.** 콘솔이 평범하게 나오고 중지가 쉽습니다.

첫 빌드가 몇 분 걸리는 것도 정상입니다. Gradle 배포판과 의존성을 처음 받으며, 특히 유레카 클라이언트의 전이 의존성 트리가 큽니다.

#### PowerShell 의 `curl` 은 진짜 curl 이 아닙니다

`Invoke-WebRequest` 의 별칭이라 응답이 객체로 감싸져 나옵니다. 원문을 보려면 **확장자까지 적습니다.**

```powershell
curl.exe http://localhost:8095/actuator/health
```

설정 서버의 응답처럼 긴 JSON 은 브라우저로 여는 편이 편합니다.

#### 실행 시 `Command line is too long`

실행하면 애플리케이션이 뜨기 전에 아래 오류가 납니다.

```
Error running 'XxxApplication'. Command line is too long.
Shorten the command line and rerun.
```

스프링 문제가 아니라 Windows 의 명령줄 길이 제한(32,767자)에 걸린 것입니다. 의존성이 많아 클래스패스 문자열이 그 한도를 넘습니다.

```
Run/Debug Configurations → Modify options → Shorten command line → JAR manifest
```

클래스패스를 임시 jar 의 매니페스트에 넣어 명령줄에서 빼는 방식입니다.

#### `.properties` 파일의 한글이 깨짐

`gradle.properties` 의 한글 주석이 `?` 나 알 수 없는 문자로 보인다면 편집기 인코딩 문제입니다.

```
Settings → Editor → File Encodings
  Default encoding for properties files      UTF-8
  Transparent native-to-ascii conversion     체크 해제
```

**설정을 바꾸기 전에 그 파일을 저장하지 않습니다.** 두 가지 상태가 있는데 겉보기로는 구분되지 않습니다.

| 보이는 모습 | 상태 |
|---|---|
| `ê³µíµ 모ë` 처럼 알 수 없는 문자 | 파일은 정상이고 화면만 깨진 것입니다. 설정을 바꾸면 복구됩니다 |
| `# ?? ??` 처럼 물음표 | 이미 그 인코딩으로 저장되어 원본이 사라진 것입니다. 복구되지 않습니다 |

첫 번째 상태에서 파일을 저장하면 두 번째로 넘어갑니다. 한글이 ISO-8859-1 에 없어 물음표로 대체되기 때문입니다.

### 1-8. IntelliJ 에 표시되는 경고 두 가지는 정상입니다

세팅을 마쳐도 IntelliJ 가 아래 두 가지를 빨간 줄로 표시합니다. 컴파일과 기동에는 영향이 없습니다.

| 표시되는 곳 | 이유 |
|---|---|
| `<서비스명>Application.java` 의 `@EntityScan`·`@EnableJpaRepositories` 안에 있는 `com.pawtrail.common` | 공통 모듈이 아직 의존성에 없어서입니다. 3장에 따라 공통 모듈을 연결하면 사라집니다 |
| config 저장소의 `app.auditor.system-name`, `app.outbox.relay.enabled`, `app.logging.loki.url` | 이 프로젝트가 직접 정의한 프로퍼티라 스프링이 아는 목록에 없어서입니다. 서비스 저장소가 아니라 config 저장소에 있으므로 여기서는 보이지 않습니다 |

`@EntityScan` 과 `@EnableJpaRepositories` 의 `basePackages` 는 **문자열을 받습니다.** 컴파일러 입장에서는 일반 문자열과 다를 것이 없으므로, 해당 패키지가 실제로 없어도 컴파일과 기동이 정상적으로 이루어집니다. 런타임에 스캔했을 때 아무것도 찾지 못하고 끝날 뿐입니다.

---

## 2. 로컬 실행 환경

### 2-1. 무엇이 어디서 도는가

로컬에서는 인프라와 플랫폼을 Docker Compose로 띄우고, **지금 작업 중인 도메인 서비스만 IntelliJ에서 직접 실행합니다.** 도메인 서비스를 전부 컨테이너로 올리면 메모리 부족이 발생 할 수 있고, 코드를 고칠 때마다 이미지를 다시 만들어야 해 개발 속도가 크게 떨어집니다.

| 어디서 도는가 | 무엇이 |
|---|---|
| AWS EC2 (팀 공용) | PostgreSQL 하나 |
| 내 PC · Docker Compose | Kafka, Redis, 관측 스택, nginx, gateway, config, eureka |
| 내 PC · IntelliJ | 지금 작업 중인 서비스 1~3개 |

PostgreSQL만 공용으로 두는 이유는 수집한 데이터를 함께 쓰기 위해서입니다. 수집 API에 하루 호출 제한이 있어 데이터를 채우는 데 여러 날이 걸리고, 추출 배치는 GPU가 필요해 각자 재현할 수 없습니다.

Kafka를 각자 로컬에 두는 이유는 반대입니다. 공용으로 쓰면 한 사람이 발행한 이벤트를 다른 사람의 컨슈머가 가져가 버려 서로의 테스트가 섞입니다.

### 2-2. Compose 프로파일

| 프로파일 | 포함 | 언제 켜는가 | 필수 |
|---|---|---|---|
| `infra` | Kafka, Redis | 거의 항상 | **필수** |
| `platform` | gateway, eureka, config | 설정을 받고 게이트웨이를 거친 호출을 확인할 때 | **필수** |
| `tools` | Kafka UI | 토픽에 메시지가 실제로 실렸는지 확인할 때 | 권장 |
| `observability` | Prometheus, Grafana, Loki, Zipkin | 로그·메트릭·추적을 볼 때 | 선택 |
| `db` | PostgreSQL | 공용 인스턴스를 쓸 수 없을 때만 | 선택 |
| `edge` | nginx | 프론트엔드와 함께 확인할 때 | 선택 |
| `pipeline` | ingest, extract | 수집·추출 배치를 돌릴 때만 | 선택 |
| `app` | 도메인 서비스 전체 | 배포 검증 때만 | 선택 |

`infra` 와 `platform` 이 필수인 이유는 **`platform` 이 빠지면 config-server가 없어 이 서비스가 데이터베이스 주소와 포트를 받지 못하고 기동에 실패하기 때문**입니다. 증상이 "포트가 8080으로 뜨고 datasource를 만들지 못함"으로 나타나 원인이 프로파일이라는 것이 드러나지 않습니다.

기본 조합은 `infra,platform,tools` 이며 `infra` 저장소의 `.env` 에 지정되어 있습니다. 그 파일은 커밋되지 않으므로 각자 자기 환경에 맞게 바꿔도 됩니다. 조합별 안내와 메모리 배분은 `infra` 저장소의 README 3절에 있습니다.

```bash
# infra 레포의 .env 에 평소 조합이 지정되어 있어 옵션 없이 뜹니다
docker compose up -d

# 다른 조합이 필요할 때
docker compose --profile db up -d

# 내리기
docker compose down
```

**`db` 프로파일은 평소에 켜지 않습니다.** PostgreSQL 은 공용 인스턴스를 쓰며, 로컬 인스턴스가 함께 떠 있으면 어느 쪽에 연결되었는지 헷갈립니다. `db` 로 띄운 컨테이너는 `docker compose down` 만으로는 내려가지 않으므로 `--profile db down` 을 사용합니다.

**빌드할 때 쓰는 데이터베이스는 이 프로파일과 무관합니다.** `./gradlew build` 가 도는 동안에는 테스트가 자기 PostgreSQL 컨테이너를 직접 띄웠다가 끝나면 지웁니다. 그래서 `db` 프로파일을 켜 두지 않아도 빌드가 통과하고, 켜 두더라도 테스트는 그쪽을 쓰지 않습니다. 두 가지가 모두 Docker 위에서 돌지만 띄우는 주체가 다릅니다.

`infra` 와 `platform` 을 띄운 뒤 자기 서비스를 IntelliJ 에서 실행하면, 서비스가 유레카에 등록되고 게이트웨이를 통해 호출할 수 있게 됩니다. 다른 서비스를 호출해야 한다면 그 서비스도 IntelliJ 에서 함께 띄웁니다.

Compose 파일은 이 레포가 아니라 infra 레포에 있습니다. Redis 와 Kafka 는 사람당 하나만 떠 있어야 하므로 서비스 레포마다 두지 않습니다.

### 2-3. 포트 배정

포트가 겹치면 뒤에 뜬 서비스가 기동에 실패합니다. 아래 배정을 따릅니다.

**플랫폼**

| | 포트 |
|---|---|
| nginx | 80 |
| gateway | 8080 |
| eureka | 8761 |
| config | 8888 |
| 프론트엔드 (Vite) | 5173 |

**도메인 서비스**

| 서비스 | 포트 | | 서비스 | 포트 |
|---|---|---|---|---|
| auth | 8081 | | extract | 8089 |
| user | 8082 | | congestion | 8090 |
| pet | 8083 | | route | 8091 |
| place | 8084 | | report | 8092 |
| policy | 8085 | | notification | 8093 |
| verdict | 8086 | | review | 8094 |
| search | 8087 | | **template** | **8095** |
| ingest | 8088 | | | |

`template-service` 8095 는 이 저장소를 그대로 띄워 확인할 때 쓰는 자리입니다. 배포 대상이 아니며 config 저장소에 `template-service.yml` 이 있습니다.

**인프라**

Redis 6379 / Kafka 9092 · **29092** / Kafka UI 9000 / Prometheus 9090 / Grafana 3000 / Loki 3100 / Zipkin 9411 / PostgreSQL 5432(공용 인스턴스)

**Kafka 는 29092 로 접속합니다.** 9092 는 컨테이너끼리 쓰는 주소여서, 호스트에서 붙으면 브로커가 되돌려주는 `kafka:9092` 를 해석하지 못해 연결에 실패합니다.

프론트엔드를 3000 이 아니라 5173 에 두는 이유는 Grafana 가 3000 을 쓰기 때문입니다.

배포 환경에서 인스턴스를 여러 개 띄우는 서비스는 **기본 포트 + 100 단위**로 배정합니다. verdict 는 8086·8186·8286, search 는 8087·8187, gateway 는 8080·8180 입니다. 로컬에서는 인스턴스가 하나씩이므로 기본 포트만 사용합니다.

### 2-4. 메모리 주의

메모리 16GB를 기준으로 컨테이너마다 메모리 상한을 걸어두었고, JVM을 쓰는 컨테이너에는 힙 상한을 함께 지정했습니다.

**힙 상한을 지정하지 않으면 컨테이너가 아무 로그도 남기지 않고 종료됩니다.** JVM은 컨테이너에 걸린 상한과 무관하게 물리 메모리의 일정 비율까지 힙을 늘리려 하기 때문에, 컨테이너 상한을 넘는 순간 강제 종료됩니다. 원인을 추적하기 어려운 형태로 죽으므로 `-XX:MaxRAMPercentage`로 컨테이너 상한 대비 비율을 지정합니다.

Apple Silicon 맥에서는 arm64 이미지가 있는지 확인합니다. Kafka는 arm64를 지원하는 공식 이미지를 사용합니다.

### 2-5. 공용 DB에 연결하기

PostgreSQL 인스턴스는 하나지만 그 안에 서비스별 DB와 전용 계정이 나뉘어 있습니다. 각 계정은 **자기 DB에만 접속할 수 있습니다.** 다른 서비스의 DB에 붙으려 하면 거부되는데, 이는 설정 오류가 아니라 의도된 격리입니다.

**접속 정보는 서비스 저장소가 아니라 config 저장소에 있습니다.** 어디에 무엇이 있는지는 이렇습니다.

```
config/application-local.yml    app.datasource.host          ${DB_HOST}
config/<서비스명>.yml            spring.datasource.url        jdbc:postgresql://${app.datasource.host}:5432/place_db
                                spring.datasource.username   place_svc
config/application.yml          spring.datasource.password   ${SERVICE_DB_PASSWORD}
```

주소를 한 곳(`app.datasource.host`)에만 적고 서비스별 파일이 그것을 참조하는 이유는, **장애로 데이터베이스를 승격했을 때 그 한 줄만 고치면 모든 서비스가 따라오기 때문**입니다. 서비스마다 전체 주소를 적어 두면 열네 곳을 고쳐야 합니다.

주소와 비밀번호는 팀 내부에서 전달받아 환경 변수로 넣습니다. **어느 설정 파일에도 값을 직접 적지 않습니다.**

```
# .env  (Docker Compose 가 읽습니다)
DB_HOST=<전달받은 주소>
SERVICE_DB_PASSWORD=<전달받은 비밀번호>
```

**`SERVICE_DB_PASSWORD` 는 infra 저장소의 `.env` 에 넣은 값과 같아야 합니다.** 계정을 만들 때 쓰는 값과 접속할 때 쓰는 값이 같은 것이므로 이름도 같게 두었습니다.

주소를 환경 변수로 두는 이유는 EC2 를 재생성하면 주소가 바뀌기 때문입니다. 각자 `.env` 한 줄만 고치면 되고 저장소를 손댈 일이 없습니다.

접속하려면 **본인의 공인 IP 가 보안그룹에 등록되어 있어야 합니다.** 인터넷 회선이 바뀌거나 공인 IP 가 갱신되면 다시 등록해야 하므로, 접속이 갑자기 안 될 때 이 부분을 먼저 확인합니다.

`ddl-auto`는 반드시 `validate`로 둡니다. `update`나 `create`로 두면 애플리케이션이 공용 DB의 스키마를 마음대로 바꾸거나 데이터를 지웁니다. 수집에 여러 날이 걸린 데이터가 사라질 수 있습니다.

### 2-6. Flyway는 공용 DB를 바꿉니다

스키마는 Flyway 스크립트로 관리합니다. 그런데 DB가 공용이므로, **내가 실행한 마이그레이션이 그대로 팀원 환경에도 반영됩니다.**

- 새 마이그레이션 스크립트를 추가했다면 팀원에게 알립니다
- 이미 적용된 스크립트 파일은 고치지 않습니다. 내용이 바뀌면 체크섬이 달라져 다음 기동이 실패합니다. 고쳐야 한다면 새 번호로 스크립트를 하나 더 만듭니다
- 서비스별 스크립트는 `V20`부터 시작합니다. `V1`부터 `V19`는 공통 모듈이 사용합니다

### 2-7. 빌드할 때 뜨는 데이터베이스

`./gradlew build` 를 돌리면 **PostgreSQL 컨테이너가 하나 떴다가 사라집니다.** 테스트가 직접 띄우는 것이며, 위에서 설명한 공용 DB나 `db` 프로파일과는 아무 관계가 없습니다.

| | 개발할 때 | 빌드할 때 |
|---|---|---|
| 무엇이 쓰나 | 개발 도구에서 띄운 서비스 | `contextLoads()` |
| 데이터베이스 | 공용 PostgreSQL | 테스트가 띄운 컨테이너 |
| 누가 준비하나 | 사람이 미리 세워 둠 | 테스트가 알아서 |
| 데이터 | 남습니다 | 매번 사라집니다 |
| 사는 동안 | 개발하는 내내 | 몇 초 |

#### 왜 이렇게 하는가

`contextLoads()` 는 애플리케이션을 통째로 한 번 띄워 **빈 배선이 깨지지 않았는지** 확인하는 검사입니다. 그런데 애플리케이션이 뜨려면 `DataSource` 가 필요하고, 그 주소는 설정 서버에서 내려옵니다.

`spring.config.import` 에 `optional:` 이 붙어 있어 **설정 서버가 없어도 조용히 넘어간 뒤 `DataSource` 를 만들다 실패합니다.** 그대로 두면 이 검사가 설정 서버 기동 여부에 따라 되다 말다 하므로 검사로서 의미가 없고, 설정 서버가 없는 CI에서는 항상 실패합니다.

그래서 테스트가 외부에 기대지 않도록 데이터베이스를 스스로 준비합니다. Docker가 떠 있어야 하지만, 평소 개발에 컨테이너를 띄워 두므로 추가 조건은 아닙니다.

#### 무엇이 검증되는가

메모리 데이터베이스가 아니라 실제 PostgreSQL을 쓰는 이유입니다.

- **Flyway 스크립트가 실제로 실행됩니다.** `V1`, `V2`, 그리고 이 서비스의 `V20` 까지 돌아갑니다
- **엔티티와 스키마가 대조됩니다.** `ddl-auto` 가 `validate` 이므로 컬럼 이름이나 타입이 어긋나면 빌드가 실패합니다
- 공통 모듈의 자동 설정 6개가 실제로 켜지는지도 함께 드러납니다

#### 좌표 타입을 쓰는 서비스는 이미지를 바꿉니다

기본값은 `postgres:17-alpine` 입니다. arm64를 지원해 Apple Silicon에서 에뮬레이션 없이 돕니다.

**search, route, place 는 PostGIS가 필요하므로** `TemplateApplicationTests` 의 이미지 이름을 `postgis/postgis:17-3.5` 로 바꿉니다. 그 이미지는 amd64 전용이라 Apple Silicon에서는 Docker Desktop의 Rosetta를 켜야 합니다.

#### 테스트 설정 파일은 main 쪽을 가립니다

`src/test/resources/application.yml` 은 `src/main/resources/application.yml` 을 **덮어쓰는 것이 아니라 통째로 가립니다.** 클래스패스에서 `application.yml` 을 하나만 찾는데 테스트 리소스가 앞서기 때문입니다.

그래서 main 쪽에 있던 값도 필요하면 다시 적어야 합니다. 지금 옮겨 적은 것은 둘입니다.

| 값 | 빠뜨리면 |
|---|---|
| `spring.application.name` | 로그의 서비스 이름과 유레카 등록 이름이 `unknown` 이 됩니다 |
| `spring.profiles.default` | 프로파일이 `local` 이 아니게 되어 **Loki 로 로그를 보내려다 실패하고, 테스트 출력이 연결 오류 스택트레이스로 뒤덮입니다** |

나머지 넷(`spring.cloud.config.enabled`, `eureka.client.enabled`, `spring.flyway.locations`, `spring.jpa.hibernate.ddl-auto`)은 config 저장소 1계층의 사본입니다. **설정 서버를 껐으므로 1계층 값이 하나도 내려오지 않기 때문**이며, config 저장소에서 그 값을 바꾸면 이 파일도 함께 봅니다.

#### 라이브러리 사용 시 주의 3가지

Testcontainers 2.x 는 1.x와 여러 곳이 다릅니다. **인터넷 예제 대부분이 1.x 기준이므로 그대로 가져오면 걸립니다.**

**하나 — 아티팩트 이름에 접두사가 붙습니다.** 2.0부터의 규칙이며, 접두사 없는 옛 이름은 1.x 버전까지만 존재합니다.

```groovy
// 1.x
'org.testcontainers:postgresql'
// 2.x
'org.testcontainers:testcontainers-postgresql'
```

**둘 — 버전을 직접 적어야 합니다.** Spring Boot가 버전을 관리하는 것은 `spring-boot-testcontainers` 하나뿐이고, `testcontainers-bom` 에는 접두사가 붙은 이름들이 빠져 있어 가져와도 해결되지 않습니다. `gradle.properties` 의 `testcontainersVersion` 으로 지정하며, **전이로 들어오는 코어와 같은 값이어야 합니다.**

```powershell
./gradlew dependencies --configuration testCompileClasspath | Select-String "testcontainers"
```

**셋 — 컨테이너 클래스의 패키지가 바뀌었습니다.** 2.x 는 제네릭이 없는 `org.testcontainers.postgresql.PostgreSQLContainer` 입니다. 제네릭이 붙은 옛 클래스는 하위 호환용으로 남아 있고 사용이 권장되지 않습니다.

```java
// 옛 클래스 — 컴파일은 통과하지만 경고가 남습니다
import org.testcontainers.containers.PostgreSQLContainer;
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

// 현재 클래스
import org.testcontainers.postgresql.PostgreSQLContainer;
static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
```

**클래스 이름 뒤에 `<?>` 가 붙어 있으면 옛 클래스를 쓰고 있다는 뜻입니다.**

이 경고는 Gradle 빌드 캐시에 가려집니다. 소스가 바뀌지 않으면 컴파일을 다시 하지 않아 경고도 찍히지 않으므로, 고친 뒤 확인할 때는 강제로 다시 컴파일합니다.

```powershell
./gradlew clean build --rerun-tasks
```

---

## 3. 공통 모듈 가져오기와 버전 올리기

공통 모듈은 GitHub Packages에 jar로 배포되어 있습니다. 각 서비스는 이를 의존성으로 당겨 씁니다.

### 3-1. 인증 환경변수 설정

GitHub Packages는 공개 저장소라도 내려받을 때 인증을 요구합니다. 따라서 **빌드하기 전에 환경변수 두 개를 설정해야 합니다.** 설정하지 않으면 빌드가 401로 실패하는데, 오류 메시지에 원인이 드러나지 않아 찾는 데 시간이 걸립니다.

| 환경변수 | 값 |
|---|---|
| `GPR_USER` | 본인의 GitHub 사용자명 |
| `GPR_TOKEN` | GitHub Personal Access Token |

토큰 권한은 내려받기만 한다면 `read:packages` 하나면 충분합니다. 공통 모듈을 배포하는 담당자만 `write:packages`를 추가로 부여받습니다.

토큰은 절대 `build.gradle` 이나 레포 안 파일에 직접 적지 않습니다. 커밋에 함께 올라갑니다.

**환경변수가 없으면 빌드가 실패합니다.** 공통 모듈 의존성이 이미 선언되어 있어 내려받기를 시도하기 때문이며, 인증에 실패하면 `Received status code 401` 로 나타납니다. 원인이 토큰이라는 것이 메시지에 드러나지 않으므로 이 절을 먼저 확인합니다.

### 3-2. build.gradle에서 고치는 부분

버전은 `gradle.properties`에 한 줄로 두고, `build.gradle`이 그 값을 참조합니다.

```properties
# gradle.properties
commonVersion=0.0.4

# 라이브러리 버전도 같은 곳에서 관리합니다
querydslVersion=7.6
springdocVersion=3.1.0
```

```groovy
// build.gradle
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/paw-trail/common")
        credentials {
            username = System.getenv("GPR_USER")
            password = System.getenv("GPR_TOKEN")
        }
    }
}

dependencies {
    implementation "com.pawtrail:common:${commonVersion}"
}
```

버전을 `build.gradle`에 직접 적지 않고 `gradle.properties`로 뺀 이유는, 고쳐야 할 자리를 파일 하나로 고정하기 위해서입니다. 레포가 여러 개이므로 버전을 올릴 때 어디를 봐야 하는지가 매번 같아야 합니다.

### 3-3. 공통 모듈 버전을 올리는 절차

1. 공통 모듈 레포에서 `version`을 올리고 GitHub Packages에 배포합니다
2. 이 서비스 레포의 `gradle.properties`에서 `commonVersion` 값을 새 버전으로 고칩니다
3. Gradle을 새로 고칩니다

공통 모듈은 릴리스 버전으로 고정해 사용합니다. 즉 버전을 올리지 않으면 계속 예전 버전으로 빌드됩니다. 컴파일은 정상적으로 되기 때문에 알아채기 어려우므로, **공통 모듈이 변경되었다는 공지를 받으면 이 값부터 확인합니다.**

### 3-4. 로깅과 프로파일

템플릿에는 `src/main/resources/logback-spring.xml` 이 들어 있습니다. 콘솔 출력과 Loki 전송을 함께 설정하며, 서비스마다 고칠 것은 없습니다.

Loki 전송용 appender 정의는 공통 모듈 jar 안의 `logback-loki-appender.xml` 에 있고, 템플릿의 `logback-spring.xml` 이 그것을 `include` 해서 사용합니다. 파일을 나눈 이유는 `logback-spring.xml` 이라는 이름이 클래스패스에서 하나만 읽히기 때문입니다. 서비스 레포가 jar 보다 앞서므로 공통 모듈에 같은 이름을 두면 무시됩니다.

**`<springProfile>` 은 최상위에만 둘 수 있습니다.** `<root>`·`<appender>`·`<logger>` 안에 넣으면 아래 경고가 나고 동작이 보장되지 않습니다.

```
<springProfile> elements cannot be nested within an <appender>, <logger> or <root> element
```

logback 은 `<springProfile>` 을 먼저 처리하는데 `<root>` 안쪽은 나중에 처리하므로 평가 시점이 어긋나기 때문입니다. 그래서 템플릿은 **프로파일마다 `<root>` 를 따로 두는 형태**로 되어 있습니다. `local` 에서는 appender 정의를 `include` 하지도 않아 아예 만들어지지 않습니다.

파일 이름은 반드시 `logback-spring.xml` 이어야 합니다. `logback.xml` 로 두면 스프링 확장이 걸리지 않아 `<springProfile>` 과 `<springProperty>` 가 **오류 없이 조용히 무시됩니다.**

**전송 여부는 프로파일이 결정합니다.**

| 프로파일 | 언제 | Loki 전송 |
|---|---|---|
| `local` | IntelliJ 에서 실행 | 하지 않음 |
| `dev` | 컨테이너에서 실행 | 함 |

`application.yml` 에 `spring.profiles.default: local` 이 들어 있어, 아무것도 지정하지 않으면 `local` 로 동작합니다. `active` 가 아니라 `default` 인 것이 중요합니다. `default` 는 아무도 지정하지 않았을 때만 적용되므로, 컨테이너에서 `SPRING_PROFILES_ACTIVE=dev` 를 주면 그쪽이 우선합니다.

IntelliJ 에서 Loki 전송까지 확인하고 싶다면 실행 구성의 환경 변수에 다음을 추가합니다.

```
SPRING_PROFILES_ACTIVE=dev
```

기동 로그 첫머리의 `The following 1 profile is active` 줄로 어느 쪽인지 확인할 수 있습니다.

메트릭과 추적은 프로파일과 무관하게 항상 전송됩니다. Prometheus 는 서비스의 `/actuator/prometheus` 를 직접 수집하고, 추적은 애플리케이션이 Zipkin 으로 보냅니다.

---

## 4. 설정값을 어디에 두는가

설정값은 성격에 따라 네 곳으로 나뉩니다. 읽히는 시점과 바꿀 때 드는 비용이 서로 다르므로 섞어 쓰지 않습니다.

| 두는 곳 | 언제 읽히는가 | 무엇을 두는가 | 바꾸려면 |
|---|---|---|---|
| 레포 안 `gradle.properties` | 빌드할 때 (Gradle) | 빌드에만 쓰이는 값 | 다시 빌드 |
| 레포 안 `application.yml` | 기동할 때 | **세 줄뿐입니다** (아래 참고) | 재배포 |
| **config 저장소** | 기동·갱신할 때 | 그 밖의 거의 모든 값 | **커밋만 하면 됩니다** |
| 환경 변수 | 빌드·기동 시점 | 비밀값, 사람마다 다른 값 | 컨테이너 재시작 |

**대부분의 값은 config 저장소에 있습니다.** 서비스 저장소의 `application.yml` 에는 세 줄만 남깁니다.

```yaml
spring:
  application:
    name: place-service
  config:
    import: "optional:configserver:http://${CONFIG_HOST:localhost}:8888"
  profiles:
    default: local
```

**config 저장소로 옮긴 값을 서비스 저장소에 남겨 두지 않습니다.** 같은 키가 두 곳에 있으면 어느 쪽이 이기는지 매번 확인해야 합니다.

### 4-1. config 저장소의 4계층

값은 네 파일에 나뉘어 있고, **계층 번호가 곧 세기입니다. 숫자가 큰 쪽이 이깁니다.**

| 계층 | 파일 | 적용 범위 | 예 |
|:---:|---|---|---|
| 1 | `application.yml` | 모든 서비스 · 모든 환경 | `ddl-auto`, Flyway `locations`, Kafka 직렬화기, 액추에이터 노출 |
| 2 | `{서비스명}.yml` | 해당 서비스 · 모든 환경 | 포트, 데이터베이스 이름과 계정, outbox relay 스위치 |
| 3 | `application-{env}.yml` | 모든 서비스 · 해당 환경만 | 데이터베이스 호스트, Kafka · Redis · 유레카 · Loki · Zipkin 주소 |
| 4 | `{서비스명}-{env}.yml` | 해당 서비스 · 해당 환경만 | 되도록 비워 둡니다 |

**"구체적인 파일이 이긴다"가 아닙니다.** 규칙은 두 겹입니다.

1. 프로파일이 붙은 파일이 안 붙은 파일을 이깁니다
2. 같은 조건 안에서는 서비스별 파일이 공통 파일을 이깁니다

그래서 **3계층이 2계층을 이깁니다.** 환경별 공통값을 특정 서비스만 다르게 하고 싶다면 2계층이 아니라 **4계층에 적어야 합니다.** 2계층에 적으면 덮여서 반영되지 않습니다.

값을 추가할 때는 **"서비스마다 다른가 / 환경마다 다른가"** 두 가지만 판단합니다. 애매하면 번호가 작은 계층에 둡니다. 나중에 큰 번호에서 덮어쓰는 것이 반대보다 쉽습니다.

### 4-2. 환경 프로파일

프로파일은 3개이며 축의 기준은 **어디에서 실행되는가** 입니다.

| 프로파일 | 실행 위치 |
|---|---|
| `local` | IntelliJ에서 직접 실행 |
| `dev` | 로컬 `docker compose` 의 `app` 프로파일 |
| `prod` | AWS EC2 |

지정하지 않으면 `local` 로 동작하며 Loki 전송이 꺼집니다. 컨테이너에서는 `SPRING_PROFILES_ACTIVE=dev` 가 이깁니다.

**프로파일 이름에 하이픈을 쓰지 않습니다.** 하이픈이 있으면 설정 서버가 서비스명과 프로파일을 가르지 못해 설정을 받아오지 못합니다.

### 4-3. 환경변수를 어디에 넣는가

환경 변수는 넣는 위치가 실행 방법에 따라 다릅니다. 여기서 자주 막히므로 세 경우를 구분합니다.

| 실행 방법 | 어디에 넣는가 |
|---|---|
| Docker Compose로 컨테이너를 띄울 때 | 프로젝트 루트의 `.env` 파일. Compose가 자동으로 읽습니다 |
| IntelliJ에서 서비스를 직접 실행할 때 | 실행 구성(Run/Debug Configurations)의 Environment variables 칸 |
| Gradle 빌드(공통 모듈 내려받기) | OS 환경변수. IntelliJ 실행 구성에 넣어도 Gradle 빌드에는 적용되지 않습니다 |

**IntelliJ에서 직접 실행하는 서비스는 `.env` 파일을 읽지 않습니다.** `.env` 는 Docker Compose 가 읽는 파일이므로, IntelliJ 로 띄우는 서비스에 `SERVICE_DB_PASSWORD` 가 필요하다면 실행 구성에 직접 넣어야 합니다.

레포에 들어 있는 `.env.example` 을 복사해 `.env` 를 만들고 값을 채웁니다.

```bash
cp .env.example .env
```

`.env` 는 커밋하지 않습니다. `.gitignore` 에 이미 포함되어 있습니다.

### 4-4. 서비스가 사용하는 주요 설정값

| 키 | 두는 곳 | 값 | 무엇을 하는가 |
|---|---|---|---|
| `commonVersion` | 레포 안 `gradle.properties` | 예: `0.0.4` | 공통 모듈 버전 |
| `GPR_USER` / `GPR_TOKEN` | OS 환경변수 | GitHub 계정·토큰 | 공통 모듈 내려받기 |
| `CONFIG_HOST` | 환경변수 | 기본값 `localhost` | 설정 서버 주소. 컨테이너와 AWS 에서만 지정합니다 |
| `DB_HOST` | 환경변수 (`.env`) | 개발용 PostgreSQL 주소 | config 저장소의 `app.datasource.host` 가 이 값을 참조합니다 |
| `SERVICE_DB_PASSWORD` | 환경변수 (`.env`) | 서비스 계정 비밀번호 | **infra 저장소의 `.env` 에 넣은 값과 같아야 합니다.** 절대 커밋하지 않습니다 |
| `SPRING_PROFILES_ACTIVE` | 환경변수 (실행 구성 또는 Compose) | `dev` | 지정하지 않으면 `local` 로 동작하며 Loki 전송이 꺼집니다 |
| `spring.datasource.url` | config 2계층 | `jdbc:postgresql://${app.datasource.host}:5432/<서비스>_db` | 호스트는 3계층에서 참조해 옵니다 |
| `spring.datasource.username` | config 2계층 | `<서비스>_svc` | 자기 DB에만 접속할 수 있는 계정입니다. `_user` 가 아닙니다 |
| `app.datasource.host` | config 3계층 | 환경별 주소 | **데이터베이스를 승격할 때 고치는 자리가 이 한 줄입니다** |
| `app.auditor.system-name` | config 1계층 (기본 `SYSTEM`) | 배치만 2계층에서 덮음 | 인증 없이 도는 배치가 감사 컬럼에 남길 이름입니다. 없으면 배치의 INSERT 가 실패합니다 |
| `app.outbox.relay.enabled` | config 2계층 | 발행 서비스의 한 인스턴스만 `true` | 미발행 이벤트를 회수하는 스케줄러입니다 |
| `app.logging.loki.url` | config 3계층 | 환경별 주소 | logback 이 읽는 전송 주소입니다 |
| `spring.jpa.hibernate.ddl-auto` | config 1계층 | `validate` | 스키마는 Flyway가 관리하므로 애플리케이션은 검증만 합니다 |
| 외부 API 키 · OAuth 시크릿 | 환경변수 | 제공자 콘솔에서 발급 | 설정 파일에 적지 않습니다 |
| JWT 서명 키 | **개인키는 환경변수, 공개키는 config 저장소** | RS256 | ★아래 설명을 반드시 읽습니다 |

### 4-5. 비밀값은 config 저장소에 넣지 않습니다

**`paw-trail/config` 는 공개 저장소입니다.** 비밀번호·개인키·시크릿은 어느 계층에도 넣지 않고, 자리만 `${환경변수}` 형태로 남깁니다.

```yaml
spring:
  datasource:
    password: ${SERVICE_DB_PASSWORD}
```

**기본값을 함께 적지 않습니다.** `${SERVICE_DB_PASSWORD:1234}` 처럼 쓰면 환경 변수를 빠뜨려도 접속이 되어 버려 누락이 영영 드러나지 않습니다. 환경 변수가 없으면 기동이 실패하는 편이 낫습니다.

**RS256 키는 개인키와 공개키를 다르게 다룹니다.**

- **개인키**(auth 가 토큰에 서명) — 환경 변수. 유출되면 누구나 유효한 토큰을 만들 수 있습니다
- **공개키**(게이트웨이가 서명 검증) — config 저장소에 둡니다. 검증에만 쓰이므로 공개되어도 무해합니다

**한 번 커밋한 값은 지워도 이력에 남습니다.** 되돌리는 것으로 끝나지 않으며 해당 키를 새로 발급해야 합니다.

### 4-6. 값을 바꾸면 언제 반영되는가

| 바꾼 것 | 필요한 작업 |
|---|---|
| config 저장소의 값 | `main` 에 커밋하면 끝입니다. 설정 서버는 다시 띄우지 않아도 됩니다 |
| 이미 떠 있는 서비스에 반영 | `POST /actuator/refresh` 또는 재기동 |
| 레포 안 `application.yml` | 재배포 |
| 환경 변수 | 컨테이너 재시작 |

`refresh` 는 데이터베이스 커넥션 풀까지 다시 만듭니다. 그래서 데이터베이스를 승격했을 때 주소만 바꾸고 재배포 없이 전환할 수 있습니다. 이는 config 1계층의 아래 두 줄에 달려 있으므로 지우지 않습니다.

```yaml
spring:
  cloud:
    refresh:
      extra-refreshable: javax.sql.DataSource,com.zaxxer.hikari.HikariDataSource
      never-refreshable: ""
```

없으면 프로퍼티만 다시 바인딩되고 **커넥션 풀은 옛 주소를 그대로 물고 있습니다.** `refresh` 응답이 정상이고 바뀐 키가 나와도 그렇습니다.

### 4-7. 설정이 제대로 내려오는지 확인하기

```
http://localhost:8888/<서비스명>/local
```

응답의 `propertySources` 배열이 **어느 계층 파일에서 온 값인지까지 보여 주며, 배열 앞이 우선순위가 높은 쪽**입니다.

**`.yml` · `.properties` · `.json` 주소는 쓸 수 없습니다.** 설정 서버가 그 주소에서 서비스명과 프로파일을 하이픈으로 가르는데, 우리 서비스명은 모두 `place-service` 처럼 하이픈을 포함하고 있어 400 이 납니다.

포트가 8080 으로 뜬다면 2계층 파일을 못 찾은 것입니다. 파일명이 `spring.application.name` 과 정확히 같은지 확인합니다. 다르면 **오류 없이 그 계층만 빠진 채 내려갑니다.**

## 5. 4계층 — 왜 이렇게 나누는가

모든 서비스는 `presentation / application / domain / infrastructure` 4계층으로 나뉩니다.

| 계층 | 맡는 일 | 이 계층과 관련 없는 역할 |
|---|---|---|
| **presentation** | 요청을 받아 형식을 확인하고, 결과를 응답 형태로 돌려줍니다 | 무엇이 옳은지 판단하는 일 (예시: 조건을 따져 동반 가능 여부를 계산하거나, DB를 직접 조회하는 코드) |
| **application** | 어떤 일을 어떤 순서로 할지 조율하고 결과를 조립합니다. 판단 기준 자체는 갖지 않습니다 | 판단 기준을 직접 갖는 일 (예시: 판정 규칙을 여기서 계산하거나, SQL을 작성하는 코드) |
| **domain** | 이 서비스에 어떤 개념과 규칙이 필요한지를 정의합니다. 바깥에서 가져와야 하는 것은 인터페이스로 선언만 해둡니다 | 특정 기술을 다루는 일 (예시: JPA로 조회하거나, 카프카로 발행하거나, HTTP를 호출하는 코드) |
| **infrastructure** | domain이 정의해둔 인터페이스를, 실제로 사용하는 기술 스택에 맞춰 구현합니다 | 판단 기준을 갖는 일 (예시: 어떤 조건이면 동반 가능인지를 여기서 정하는 코드) |

### 핵심 원칙

> **인터페이스는 `domain`에 선언하고, 구현은 `infrastructure`에 둡니다.**

예를 들어 verdict가 policy 서비스를 호출해야 한다면 다음과 같이 나눕니다.

- `domain/provider/PolicyProvider.java` — "정책을 가져다주는 무언가"라는 **약속**만 선언합니다
- `infrastructure/provider/PolicyProviderImpl.java` — 실제로 HTTP를 호출하는 **구현**입니다

이렇게 하면 의존 방향이 항상 `domain`을 향합니다. 그 결과 두 가지를 얻습니다.

- **`RuleEngine`을 테스트할 때 다른 서비스를 띄우지 않아도 됩니다.** 가짜 `PolicyProvider`를 넣으면 됩니다
- **통신 방식이 바뀌어도 `domain` 코드는 바뀌지 않습니다.** 구현체만 교체하면 됩니다

---

## 6. 공통 모듈 (`com.pawtrail.common`)

모든 서비스가 의존성으로 당겨쓰는 라이브러리입니다. GitHub Packages로 배포합니다.

- **넣는 것** — 기술적 관심사이면서 거의 바뀌지 않는 것
- **넣지 않는 것** — 도메인 지식, 이벤트 payload DTO, 도메인 엔티티

> 공통 모듈을 고치면 이를 사용하는 모든 서비스가 버전을 올려야 합니다. 따라서 "여러 곳에서 쓰인다"만으로는 부족하고 "앞으로 거의 바뀌지 않는다"까지 만족해야 넣습니다.

### 자동 설정 6개

`config/` 패키지의 6개 클래스가 `AutoConfiguration.imports` 에 등록되어 있습니다. 서비스는 의존성만 추가하면 조건에 맞는 것이 올라옵니다.

| 클래스 | 켜지는 조건 | 등록하는 Bean |
|---|---|---|
| `CommonWebAutoConfiguration` | 서블릿 웹 + spring-webmvc | `GlobalExceptionHandler`, `TraceIdResponseAdvice` |
| `CommonSecurityAutoConfiguration` | 서블릿 웹 + spring-security | `SecurityFilterChain`(관리자 경로 보호 포함), `CustomSecurityExceptionHandler` |
| `CommonJpaAutoConfiguration` | spring-data-jpa | `AuditorProvider`, JPA Auditing 활성화 |
| `CommonMessagingAutoConfiguration` | spring-data-jpa + spring-kafka | `OutboxEventRecorder`, `OutboxPublisher`, `OutboxCommitListener`, `OutboxRelay`, `InboxProcessor` |
| `CommonKafkaAutoConfiguration` | spring-kafka | `RecordMessageConverter`, `KafkaSecurityInterceptor`, `DefaultErrorHandler` |
| `CommonAsyncAutoConfiguration` | 없음 | `@EnableAsync` |

조건 판단에 `jakarta.persistence.EntityManager` 가 아니라 `org.springframework.data.jpa.repository.JpaRepository` 를 쓰는 이유는, `hibernate-spatial` 이 `hibernate-core` 를 거쳐 `jakarta.persistence-api` 를 전이로 끌고 오기 때문입니다. JPA 스타터를 지운 무상태 서비스에서도 `EntityManager` 는 클래스패스에 남아 조건이 참이 되어버립니다.

모든 Bean 에 `@ConditionalOnMissingBean` 이 붙어 있으므로, 서비스가 같은 타입의 Bean 을 직접 정의하면 공통 모듈 쪽이 물러납니다. 로그인 경로를 열어야 하는 auth 서비스가 자체 `SecurityFilterChain` 을 정의하는 경우가 여기 해당합니다.

### 패키지 구조

```
com.pawtrail.common
│
├── config/                                         자동 설정 6개. 조건과 Bean 정의가 모두 여기 모입니다.
│   ├── CommonWebAutoConfiguration.java (class)     자동 설정 클래스는 컴포넌트 스캔에 걸리면 안 되는
│   ├── CommonSecurityAutoConfiguration.java        특수한 부류라 한 폴더에 격리해 둡니다
│   ├── CommonJpaAutoConfiguration.java
│   ├── CommonMessagingAutoConfiguration.java
│   ├── CommonKafkaAutoConfiguration.java
│   └── CommonAsyncAutoConfiguration.java
│
├── entity/
│   └── BaseEntity.java (abstract class)            모든 테이블이 상속하는 공통 컬럼 묶음입니다.
│                                                   createdAt·createdBy, updatedAt·updatedBy,
│                                                   deletedAt·deletedBy 를 가집니다. 소프트 딜리트를 쓰므로
│                                                   실제 DELETE 를 하지 않고 deletedAt 에 시각을 기록합니다.
│                                                   NULL 이면 살아있는 행입니다.
│                                                   시각은 전부 LocalDateTime 이며 DB 컬럼은 timestamp 입니다
│
├── audit/
│   └── AuditorProvider.java (class)                "지금 이 작업을 하는 주체가 누구인가"를 한 곳에서
│                                                   알려줍니다. 삭제 시 deletedBy 에 넣을 값을 여기서 얻습니다.
│                                                   생성·수정은 JPA 가 자동으로 채우는데 삭제만 수동이므로,
│                                                   값의 출처가 갈리지 않도록 두는 장치입니다.
│                                                   인증이 없으면 app.auditor.system-name 값을 씁니다
│
├── enums/
│   └── Role.java (enum)                            USER / ADMIN. 게이트웨이가 X-User-Role 로 주입하는
│                                                   값입니다
│
├── exception/
│   ├── ErrorCode.java (interface)                  에러 코드가 가져야 할 모양만 정의합니다.
│   │                                               getHttpStatus, getCode, getMessage 세 개이며
│   │                                               각 서비스가 자기 enum 으로 구현합니다.
│   │                                               getCode() 는 반드시 name() 을 그대로 반환합니다.
│   │                                               상수 이름이 곧 응답 code 이자 API 계약인데
│   │                                               규칙을 어겨도 컴파일러가 잡지 못합니다
│   ├── CommonErrorCode.java (enum)                 모든 서비스에서 같은 뜻으로 쓰이는 에러만 담습니다.
│   │                                               VALIDATION_FAILED(400)
│   │                                               AUTHENTICATION_FAILED(401)
│   │                                               ACCESS_DENIED(403)
│   │                                               INTERNAL_ERROR(500)
│   │                                               EXTERNAL_API_ERROR(502)
│   ├── CustomException.java (class)                의도적으로 던지는 모든 예외입니다. ErrorCode 를 하나
│   │                                               물고 있으며, 핸들러가 거기서 HTTP 상태와 메시지를 꺼냅니다.
│   │                                               예외 클래스를 상태별로 나누지 않는 이유는, 상태값이 이미
│   │                                               ErrorCode 에 있어 클래스가 두 번째 진실의 원천이 되면
│   │                                               둘이 어긋나도 아무도 알아채지 못하기 때문입니다
│   └── handler/
│       └── GlobalExceptionHandler.java (class)
│                                                   모든 예외를 잡아 응답 형식으로 바꾸는 곳입니다.
│                                                   핸들러는 4개입니다.
│                                                   (1) CustomException → ErrorCode 의 상태로 응답
│                                                   (2) MethodArgumentNotValidException(@Valid 실패)
│                                                       → 400 과 함께 필드별 오류 배열 반환
│                                                   (3) MethodArgumentTypeMismatchException
│                                                       (/places/abc 처럼 타입 불일치) → 400
│                                                   (4) Exception → 500
│                                                   401·403 은 여기로 오지 않습니다. 시큐리티 필터가
│                                                   DispatcherServlet 앞에 있어 아래 핸들러가 처리합니다
│
├── message/                                        이벤트 발행·수신의 뼈대
│   ├── DomainEvent.java (interface)                발행할 이벤트가 구현하는 계약입니다.
│   │                                               getTopic, getAggregateType, getAggregateId 세 개이며
│   │                                               셋 다 @JsonIgnore 라 payload 에는 나가지 않습니다.
│   │                                               이벤트가 자기 라우팅 정보를 들고 다니므로 발행할 때
│   │                                               토픽을 따로 넘기지 않습니다
│   ├── EventEnvelope.java (record)                 모든 이벤트를 감싸는 봉투입니다. eventId(중복 판단 키),
│   │                                               eventType, occurredAt, aggregateType, aggregateId,
│   │                                               data 로 구성됩니다.
│   │                                               봉투는 공통에 두지만 data 안쪽 DTO 는 각 서비스가
│   │                                               따로 정의합니다. 결합을 피하기 위함입니다.
│   │                                               제네릭에 타입 제약이 없어 받는 쪽 DTO 는
│   │                                               DomainEvent 를 구현하지 않아도 됩니다
│   ├── AuthContextHeaders.java (final class)       X-User-Id·X-User-Role 헤더 키의 단일 출처입니다.
│   │                                               HTTP 필터, 서비스 간 호출 인터셉터, 카프카 인터셉터가
│   │                                               같은 문자열을 각자 들고 있으면 어긋나도 알 수 없습니다
│   ├── KafkaSecurityInterceptor.java (class)       소비 시 카프카 헤더를 SecurityContext 로 복원합니다.
│   │                                               컨슈머는 HTTP 요청 밖 스레드에서 실행되어 컨텍스트가
│   │                                               비어 있고, 복원하지 않으면 컨슈머가 만든 행의
│   │                                               createdBy 가 전부 SYSTEM 으로 남습니다.
│   │                                               traceparent 는 다루지 않습니다. 스프링 카프카
│   │                                               Observation 이 처리하며 직접 넣으면 헤더가 중복됩니다
│   │
│   ├── outbox/                                 "DB에는 저장됐는데 이벤트는 나가지 않았다"를 막는 장치
│   │   ├── OutboxMessage.java (entity)         발행 대기 중인 이벤트 한 건입니다. 비즈니스 데이터와
│   │   │                                       같은 트랜잭션으로 저장되므로 둘 다 되거나 둘 다 안 됩니다
│   │   ├── OutboxRepository.java (interface)
│   │   │                                       미발행 건을 조회합니다. 같은 aggregateId 에 대해
│   │   │                                       앞선 미발행 건이 있는지도 확인해 순서를 보장합니다.
│   │   │                                       재시도 10회를 넘긴 건은 조회에서 제외합니다.
│   │   │                                       영구 실패 한 건이 뒤 메시지를 전부 막기 때문입니다
│   │   ├── OutboxEventRecorder.java (class)    ★서비스가 호출하는 발행 입구입니다.
│   │   │                                       record(이벤트) 한 줄로 봉투 생성·직렬화·행 저장·
│   │   │                                       커밋 후 발행 신호까지 처리합니다.
│   │   │                                       전파 속성이 MANDATORY 라 트랜잭션 없이 부르면
│   │   │                                       즉시 예외가 납니다. 비즈니스 데이터와 같은
│   │   │                                       트랜잭션이어야 Outbox 가 성립하기 때문입니다
│   │   ├── OutboxPublisher.java (class)        실제 카프카 전송과 상태 기록의 단일 지점입니다.
│   │   │                                       건당 독립 트랜잭션이라 한 건이 실패해도 앞서 성공한
│   │   │                                       건의 상태 갱신은 유지됩니다
│   │   ├── OutboxCommitListener.java (class)
│   │   │                                       커밋 직후 비동기로 발행을 시작합니다.
│   │   │                                       정상 경로는 여기서 처리되므로 지연이 거의 없습니다
│   │   └── OutboxRelay.java (class)            놓친 건을 회수하는 안전망 스케줄러입니다(5초 주기).
│   │                                           app.outbox.relay.enabled 로 한 인스턴스에서만
│   │                                           실행합니다. 여러 인스턴스가 동시에 돌면 서로
│   │                                           "앞에 미발행 건이 없다"고 판단해 순서 보장이 깨집니다
│   │
│   └── inbox/                                  "같은 이벤트를 두 번 처리했다"를 막는 장치
│       ├── ProcessedEvent.java (entity)        처리한 eventId 기록입니다. PK 충돌 자체가 멱등 장치라
│       │                                       별도 조회가 필요 없습니다.
│       │                                       ID 가 발행자에게서 온 값이라 Persistable 을 구현해
│       │                                       항상 persist 가 나가게 합니다. 그러지 않으면 merge 가
│       │                                       호출되어 PK 충돌 없이 UPDATE 로 흘러갑니다
│       ├── ProcessedEventRepository.java (interface)
│       │                                       existsById 와 save 만 사용합니다
│       └── InboxProcessor.java (class)         processOnce(eventId, topic, 로직) 형태로 사용합니다.
│                                               기록과 비즈니스 로직을 한 트랜잭션으로 묶어
│                                               "처리했다고 기록했는데 실제로는 실패"와
│                                               "처리는 했는데 기록이 실패"를 둘 다 막습니다
│
├── response/
│   ├── CommonApiResponse.java (class)              모든 API 응답의 겉껍데기입니다.
│   │                                               { code, message, data, traceId }
│   │                                               성공은 code 가 SUCCESS 입니다
│   ├── PageResponse.java (record)                  목록 응답에서 data 안에 들어가는 형태입니다.
│   │                                               { content: [...], page: { number, size,
│   │                                                 totalElements, totalPages } }
│   │                                               from(Page, 변환함수) 로 만들며 엔티티를 그대로
│   │                                               노출하지 않게 합니다
│   └── TraceIdResponseAdvice.java (class)          응답 직전에 traceId 를 채웁니다. 컨트롤러가 신경 쓸
│                                                   필요가 없고, 성공 응답에도 실립니다. 문의가 들어왔을 때
│                                                   해당 요청을 분산 추적에서 바로 찾기 위함입니다
│
└── security/
    ├── filter/HeaderAuthenticationFilter.java (class)
    │                                               게이트웨이가 넣어준 X-User-Id·X-User-Role 헤더를 읽어
    │                                               SecurityContext 를 채웁니다. 뒤쪽 서비스는 JWT 를 직접
    │                                               다루지 않습니다. 토큰 검증은 게이트웨이에서 끝났습니다.
    │                                               게이트웨이는 헤더를 넣기 전에 바깥에서 들어온 같은 이름의
    │                                               헤더를 먼저 지우므로 이 필터가 값을 그대로 믿어도 됩니다.
    │                                               반대로 이 서비스에 직접 요청을 보내면 그 헤더가 그대로
    │                                               신뢰되므로, 보안그룹으로 게이트웨이 밖에서 닿지 못하게 막아 둡니다.
    │                                               Bean 이 아니라 공통 모듈의 보안 자동 설정에서 직접 생성합니다.
    │                                               Bean 으로 두면 서블릿 전역 필터에도 등록돼 두 번 돕니다
    ├── handler/CustomSecurityExceptionHandler.java (class)
    │                                               401·403 을 공통 응답 형식으로 반환합니다.
    │                                               시큐리티 필터는 DispatcherServlet 앞이라
    │                                               GlobalExceptionHandler 가 잡지 못합니다.
    │                                               이것이 없으면 인증 실패만 응답 형태가 달라집니다
    ├── interceptor/RestClientAuthInterceptor.java (class)
    │                                               서비스가 다른 서비스를 호출할 때 X-User-Id 를 헤더에
    │                                               실어줍니다. 이것이 없으면 호출받은 쪽이 요청자를 알 수
    │                                               없어 감사 컬럼이 시스템 계정으로 기록됩니다.
    │                                               traceparent 는 넣지 않습니다. 분산 추적 라이브러리가
    │                                               자동 처리하며, 직접 넣으면 트레이스가 갈라집니다.
    │                                               ※ 아직 RestClient.Builder 에 연결되어 있지 않습니다.
    │                                                 서비스 간 호출을 처음 구현할 때 연결 방식을 정합니다
    ├── principal/CustomUserPrincipal.java (record)
    │                                               SecurityContext 에 담기는 사용자 정보입니다.
    │                                               accountId(UUID) 와 role(Role) 둘만 가집니다
    └── annotation/CurrentUser.java (annotation)
                                                    컨트롤러에서 사용자를 주입받는 애노테이션입니다

src/main/resources/
├── META-INF/spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│                                                   위 config 6개를 자동 설정으로 등록합니다.
│                                                   이 파일이 jar 에 들어가지 않으면 아무 Bean 도
│                                                   올라오지 않는데 오류는 나지 않습니다
└── db/migration/common/
    ├── V1__outbox.sql                              outbox 테이블입니다. 공통이므로 V1~V19 대역을 씁니다
    └── V2__inbox.sql                               processed_event 테이블입니다
```

### 공통 모듈 사용법

무엇이 들어 있는지보다 **어떻게 쓰는지**가 먼저 필요합니다. 자주 쓰는 6가지를 코드로 정리합니다.

#### 1) 응답 감싸기 — `CommonApiResponse`

컨트롤러의 반환 타입을 `CommonApiResponse<T>` 로 감쌉니다. `traceId` 는 응답 직전에 자동으로 채워지므로 신경 쓰지 않습니다.

```java
@GetMapping("/api/v1/places/{placeId}")
public CommonApiResponse<PlaceResponse> getPlace(@PathVariable UUID placeId) {
    return CommonApiResponse.success(placeService.getPlace(placeId));
}
```

```json
{ "code": "SUCCESS", "message": "...", "data": { ... }, "traceId": "a1b2c3..." }
```

목록은 `PageResponse` 를 함께 씁니다. **엔티티를 그대로 넘기지 않도록** 변환 함수를 인자로 줍니다.

```java
@GetMapping("/api/v1/places")
public CommonApiResponse<PageResponse<PlaceResponse>> getPlaces(Pageable pageable) {
    Page<Place> page = placeService.search(pageable);
    return CommonApiResponse.success(PageResponse.from(page, PlaceResponse::from));
}
```

#### 2) 예외 던지기 — `ErrorCode` + `CustomException`

먼저 이 서비스의 에러 코드 enum 을 만듭니다. `domain/exception/` 에 둡니다.

```java
@Getter
@RequiredArgsConstructor
public enum PlaceErrorCode implements ErrorCode {

    PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "장소를 찾을 수 없습니다."),
    PLACE_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 폐업 처리된 장소입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();       // ★반드시 name() 을 그대로 반환합니다
    }
}
```

던질 때는 `CustomException` 하나만 씁니다. 예외 클래스를 상태별로 만들지 않습니다.

```java
Place place = placeRepository.findById(placeId)
        .orElseThrow(() -> new CustomException(PlaceErrorCode.PLACE_NOT_FOUND));
```

`GlobalExceptionHandler` 가 받아서 이렇게 내보냅니다. 컨트롤러에 `try-catch` 를 쓰지 않습니다.

```
HTTP/1.1 404 Not Found
{ "code": "PLACE_NOT_FOUND", "message": "장소를 찾을 수 없습니다.", "data": null, "traceId": "..." }
```

> **enum 상수 이름이 곧 API 계약입니다.** 프론트엔드가 `code` 값으로 분기하므로, 이름을 바꾸면 컴파일러가 잡아주지 않는 계약 변경이 됩니다.

#### 3) 로그인한 사용자 꺼내기 — `@CurrentUser`

```java
@GetMapping("/api/v1/pets")
public CommonApiResponse<List<PetResponse>> getMyPets(@CurrentUser CustomUserPrincipal principal) {
    return CommonApiResponse.success(petService.findByAccount(principal.accountId()));
}
```

`CustomUserPrincipal` 은 `accountId(UUID)` 와 `role(Role)` 둘만 가집니다. 게이트웨이가 헤더로 넣어준 값이라 **토큰을 파싱하는 코드를 서비스에 두지 않습니다.**

기본 보안 체인의 규칙은 세 가지입니다.

| 경로 | 규칙 |
|---|---|
| `/internal/**`, `/actuator/**` | 인증 없이 허용 |
| **`/api/v1/admin/**`** | **`ADMIN` 역할만 허용** |
| 그 외 전부 | 인증 필수 |

관리자 API 를 공통 모듈에서 막는 이유는 **관리자 기능이 여러 서비스에 흩어져 있기 때문입니다.** 제보 처리는 report, 조건 정정은 policy, 폐업 처리는 place, 재색인은 search 에 있습니다. 각 서비스가 알아서 막게 하면 **한 곳만 빠뜨려도 그 서비스가 그대로 열립니다.**

`hasRole("ADMIN")` 이 동작하는 것은 `HeaderAuthenticationFilter` 가 권한을 `"ROLE_" + role` 형태로 만들기 때문입니다. 접두사 규칙을 바꾸면 관리자 경로가 **403 만 반환하고 원인이 드러나지 않으므로** 건드리지 않습니다.

로그인 경로처럼 열어야 하는 곳이 있으면 그 서비스가 자기 `SecurityFilterChain` 을 정의하면 되고, 그러면 공통 모듈 쪽이 물러납니다. **다만 그 경우 관리자 경로 보호도 함께 사라지므로 직접 넣어야 합니다.**

#### 4) 엔티티 만들기 — `BaseEntity`

```java
@Entity
@Table(name = "place")
@Getter
@NoArgsConstructor(access = AccessType.PROTECTED)
public class Place extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;
}
```

`createdAt`·`createdBy`·`updatedAt`·`updatedBy` 는 JPA Auditing 이 자동으로 채웁니다. **삭제만 수동입니다.**

```java
// 실제 DELETE 를 하지 않고 deletedAt 에 시각을 기록합니다
place.delete(auditorProvider.current());
```

`auditorProvider.current()` 를 쓰는 이유는 생성·수정과 **같은 출처의 값**을 넣기 위해서입니다. 인증이 없는 배치라면 `app.auditor.system-name` 값이 들어갑니다.

#### 5) 이벤트 발행하기 — `OutboxEventRecorder`

먼저 이벤트를 `domain/event/payload/` 에 정의합니다. `DomainEvent` 를 구현하면 **자기 라우팅 정보를 스스로 들고 다니게** 됩니다.

```java
public record PlaceUpdatedEvent(
        UUID placeId,
        String name,
        int version
) implements DomainEvent {

    @Override
    public String getTopic() {
        return "place.updated";
    }

    @Override
    public String getAggregateType() {
        return "Place";
    }

    @Override
    public String getAggregateId() {
        return placeId.toString();
    }
}
```

발행은 한 줄입니다.

```java
@Transactional                                    // ★반드시 트랜잭션 안에서 부릅니다
public void updatePlace(UUID placeId, PlaceUpdateCommand command) {
    Place place = placeRepository.findById(placeId)
            .orElseThrow(() -> new CustomException(PlaceErrorCode.PLACE_NOT_FOUND));

    place.update(command.name());

    outboxEventRecorder.record(new PlaceUpdatedEvent(placeId, place.getName(), place.getVersion()));
}
```

`record()` 안에서 봉투 생성 → 직렬화 → `outbox` 행 저장 → 커밋 후 발행 신호까지 전부 처리합니다. 카프카를 직접 부르는 코드는 서비스에 없습니다.

> **트랜잭션이 없으면 즉시 예외가 납니다.** 전파 속성이 `MANDATORY` 라 그렇습니다. 비즈니스 데이터와 `outbox` 행이 같은 트랜잭션으로 저장되어야 "둘 다 되거나 둘 다 안 된다"가 성립하기 때문입니다.

#### 6) 이벤트 받기 — 소비 DTO + `InboxProcessor`

**받는 쪽은 발행 서비스의 이벤트 클래스를 가져다 쓰지 않고 자기 DTO 를 정의합니다.** `infrastructure/message/kafka/consumer/dto/` 에 둡니다.

```java
@JsonIgnoreProperties(ignoreUnknown = true)       // ★필수
public record PlaceUpdatedMessage(
        UUID placeId,
        String name
) {}
```

- `@JsonIgnoreProperties` 가 없으면 **발행 쪽이 필드를 하나 추가하는 순간 이쪽이 깨지고** 두 서비스의 배포 순서가 묶입니다
- `DomainEvent` 는 구현하지 않습니다. 받는 쪽에는 토픽·집합체 값이 의미가 없습니다
- 실제로 쓰는 필드만 선언하면 됩니다. 위 예시는 `version` 을 받지 않습니다

리스너는 봉투를 타입 그대로 받습니다. 공통 모듈의 메시지 컨버터가 파라미터 타입을 읽어 역직렬화합니다.

```java
@KafkaListener(topics = "place.updated", groupId = "${spring.application.name}")
public void onPlaceUpdated(EventEnvelope<PlaceUpdatedMessage> envelope) {
    inboxProcessor.processOnce(
            envelope.eventId(),
            envelope.eventType(),
            () -> searchIndexService.reindex(envelope.data())
    );
}
```

- `topics` 문자열은 발행 쪽 `getTopic()` 이 반환하는 값과 **정확히 같아야 합니다.** 어긋나도 오류가 나지 않고 이벤트만 오지 않으므로 눈으로 확인합니다
- `processOnce` 로 감싸면 처리 이력과 비즈니스 로직이 한 트랜잭션으로 묶여 **같은 이벤트를 두 번 받아도 한 번만 처리됩니다**
- **예외는 잡지 않고 그대로 던집니다.** 컨슈머 밖으로 나가야 재시도와 DLQ 전송이 동작합니다. 1초부터 2배씩 3회 재시도하고, 그래도 실패하면 `{원본토픽}.dlq` 로 보낸 뒤 넘어갑니다

---

## 7. DB를 가진 서비스 (place 예시)

10개 서비스가 이 형태입니다. 아래 트리에서 `place`를 자기 도메인명으로 바꿔 읽으면 됩니다.

```
com.pawtrail.place
│
├── PlaceApplication.java (class)                   진입점입니다.
│                                                   @SpringBootApplication
│                                                   @EntityScan(basePackages =
│                                                       {"com.pawtrail.place", "com.pawtrail.common"})
│                                                   @EnableJpaRepositories(basePackages = { 위와 동일 })
│
│                                                   scanBasePackages 를 지정하지 않습니다. 기본값이
│                                                   이 클래스가 속한 패키지이고, 공통 모듈은 자동 설정으로
│                                                   등록되므로 스캔 대상에 넣으면 두 번 등록됩니다.
│                                                   반면 뒤의 두 애노테이션에는 공통 모듈을 지정합니다.
│                                                   Outbox·Inbox 엔티티와 레포지터리는 자동 설정이
│                                                   잡아주지 않기 때문입니다
│
├── presentation/                                   ── HTTP를 받는 층 ──
│   ├── PlaceController.java (class)                /api/v1/places — 브라우저가 호출하는 공개 API입니다
│   ├── PlaceInternalController.java (class)
│   │                                               /internal/places — 다른 서비스만 호출하는 API입니다.
│   │                                               게이트웨이가 라우팅하지 않아 외부에서 도달할 수 없습니다.
│   │                                               파일을 나눠두면 내부용임이 한눈에 드러납니다
│   └── request/
│       └── PlaceCreateRequest.java (record)        요청 바디 DTO입니다. @Valid 검증 애노테이션이 붙는
│                                                   자리이며, toCommand()로 application 층 입력으로
│                                                   변환합니다
│
├── application/                                    ── 순서를 조율하는 층 ──
│   ├── service/PlaceService.java (class)           무엇을 어떤 순서로 할지 정합니다. 저장하고, 이벤트를
│   │                                               발행하고, 결과를 조립합니다. @Transactional이 붙는
│   │                                               자리입니다
│   └── dto/
│       ├── command/
│       │   └── PlaceCreateCommand.java (record)
│       │                                           서비스에 들어가는 입력입니다
│       └── response/
│           └── PlaceResponse.java (record)         서비스가 내놓는 출력입니다. 엔티티를 그대로 담지 않고
│                                                   from(엔티티) 정적 메서드로 변환하며, 컨트롤러가 이를
│                                                   그대로 반환합니다
│
├── domain/                                         ── 이 서비스가 무엇인지 ──
│   ├── model/Place.java (entity)                   데이터와 그 데이터를 바꾸는 규칙을 가집니다
│   ├── event/
│   │   ├── PlaceEventProducer.java (interface)
│   │   │                                           이벤트를 내보낸다는 약속입니다.
│   │   │                                           카프카라는 단어가 여기 나오지 않습니다
│   │   └── payload/
│   │       └── PlaceUpdatedEvent.java (record)
│   │                                               이벤트에 실을 데이터입니다. 공통 모듈에 두지 않습니다.
│   │                                               두면 발행자가 필드를 추가할 때 소비자까지 재배포해야 합니다
│   ├── exception/PlaceErrorCode.java (enum)        이 서비스만의 에러 코드입니다. ErrorCode를 구현하며
│   │                                               PLACE_NOT_FOUND 등을 정의합니다
│   ├── provider/
│   │   ├── RawDocumentData.java (record)           다른 서비스에서 받아올 데이터의 모양입니다
│   │   └── RawDocumentProvider.java (interface)
│   │                                               그 데이터를 가져다준다는 약속입니다
│   └── repository/
│       ├── PlaceRepository.java (interface)        저장·조회의 약속입니다.
│       │                                           JPA라는 단어가 여기 나오지 않습니다
│       └── dto/
│           └── PlaceSearchConditionDto.java (record)
│                                                   조회 조건·결과 전용 객체입니다
│
├── infrastructure/                                 ── 바깥과 실제로 대화하는 층 ──
│   ├── config/                                     이 서비스만의 설정입니다
│   ├── persistence/
│   │   ├── JpaPlaceRepository.java (interface)
│   │   │                                           스프링 데이터 JPA 기본 조회입니다 (findById 등)
│   │   ├── QueryDslPlaceRepository.java (class)
│   │   │                                           복잡한 조건 조회입니다. 조회가 단순한 서비스는
│   │   │                                           이 파일을 만들지 않아도 됩니다
│   │   └── PlaceRepositoryImpl.java (class)        위 둘을 조합해 domain의 인터페이스를 구현합니다
│   ├── message/kafka/
│   │   ├── consumer/
│   │   │   ├── PlaceEventConsumer.java (class)
│   │   │   │                                       @KafkaListener입니다. 받은 이벤트를
│   │   │   │                                       InboxProcessor.processOnce로 감싸 처리합니다
│   │   │   └── dto/
│   │   │       └── PlaceIngestedMessage.java (record)
│   │   │                                           ★받는 쪽이 직접 정의하는 소비용 DTO입니다.
│   │   │                                           발행 서비스의 이벤트 클래스를 가져다 쓰지 않고
│   │   │                                           실제로 쓰는 필드만 선언합니다.
│   │   │                                           @JsonIgnoreProperties(ignoreUnknown = true)를
│   │   │                                           반드시 붙입니다. 없으면 발행 쪽이 필드를 하나
│   │   │                                           추가하는 순간 이쪽이 깨지고 배포 순서가 묶입니다.
│   │   │                                           DomainEvent는 구현하지 않습니다
│   │   └── producer/
│   │       └── PlaceEventProducerImpl.java (class)
│   │                                               OutboxEventRecorder.record()를 호출해
│   │                                               outbox에 적재합니다. 호출하는 서비스 메서드에
│   │                                               트랜잭션이 있어야 합니다
│   └── provider/client/
│       ├── RawDocumentClient.java (interface)      @HttpExchange 인터페이스입니다. 다른 서비스 호출을
│       │                                           메서드 선언만으로 정의합니다
│       ├── dto/
│       │   └── RawDocumentResponse.java (record)
│       │                                           응답을 받는 DTO입니다
│       └── RawDocumentProviderImpl.java (class)
│                                                   Client를 호출해 domain의 Provider를 구현합니다.
│                                                   응답 DTO를 domain의 Data 타입으로 변환하는 자리입니다
│
└── src/main/resources/
    ├── application.yml                             서비스명, 포트, DB 접속, 유레카 등록 등을 정의합니다
    └── db/migration/
        └── V20__place.sql                          서비스별 마이그레이션은 V20부터 시작합니다.
                                                    V1~V19는 공통 모듈이 사용하는 대역입니다.
                                                    PK는 uuid이며 애플리케이션이 v7을 생성해 넣습니다
```


### 요청 하나가 지나가는 길

위 폴더들이 실제로 어떤 순서로 엮이는지입니다. **새 기능을 만들 때 이 순서대로 파일을 만들면 됩니다.**

`POST /api/v1/places` 로 장소를 등록하는 경우입니다.

```
① presentation/request/PlaceCreateRequest.java
   요청 바디를 받고 @Valid 로 형식을 검사합니다.
   toCommand() 로 application 층 입력으로 바꿉니다.
        ↓
② presentation/PlaceController.java
   @PostMapping 하나. 서비스를 부르고 CommonApiResponse 로 감싸 반환합니다.
   여기에 if 문이 들어가기 시작하면 판단이 새어 나온 것입니다.
        ↓
③ application/dto/command/PlaceCreateCommand.java
   서비스에 들어가는 입력입니다. 요청 DTO 와 분리하는 이유는
   HTTP 형태가 바뀌어도 서비스 시그니처가 안 바뀌게 하기 위함입니다.
        ↓
④ application/service/PlaceService.java
   @Transactional 이 붙는 자리입니다.
   저장하고, 이벤트를 기록하고, 응답을 조립하는 순서를 정합니다.
   "조건이 맞는지" 같은 판단은 여기서 하지 않고 domain 에 맡깁니다.
        ↓
⑤ domain/model/Place.java
   데이터와 그 데이터를 바꾸는 규칙입니다. setter 대신
   update(...) 같은 의미 있는 메서드를 둡니다.
        ↓
⑥ domain/repository/PlaceRepository.java
   "저장한다"는 약속만 선언합니다. JPA 라는 단어가 나오지 않습니다.
        ↓
⑦ infrastructure/persistence/PlaceRepositoryImpl.java
   위 약속을 JpaPlaceRepository·QueryDslPlaceRepository 로 구현합니다.
        ↓
⑧ domain/event/payload/PlaceUpdatedEvent.java
   발행할 이벤트입니다. DomainEvent 를 구현합니다.
        ↓
⑨ infrastructure/message/kafka/producer/PlaceEventProducerImpl.java
   OutboxEventRecorder.record() 를 호출합니다.
   ④의 트랜잭션 안에서 불려야 합니다.
        ↓
⑩ application/dto/response/PlaceResponse.java
   from(엔티티) 로 변환합니다. 컨트롤러가 이것을 그대로 반환합니다.
```

**막히기 쉬운 자리 3가지**

| 증상 | 원인 |
|---|---|
| `IllegalTransactionStateException` 이 나며 이벤트 발행이 실패합니다 | ④에 `@Transactional` 이 없습니다. `OutboxEventRecorder` 는 트랜잭션 없이 부를 수 없습니다 |
| 이벤트를 발행했는데 받는 서비스가 반응하지 않습니다 | 토픽 문자열이 어긋났거나, 받는 쪽 `groupId` 가 겹쳤습니다. Kafka UI(`tools` 프로파일)로 토픽에 메시지가 실렸는지부터 확인합니다 |
| 응답 JSON 에 엔티티 필드가 그대로 노출됩니다 | ⑩을 거치지 않고 엔티티를 반환했습니다. 컨트롤러 반환 타입이 `CommonApiResponse<Place>` 가 아닌지 확인합니다 |

**다른 서비스를 호출해야 한다면** ⑥⑦ 대신 `domain/provider/` 에 인터페이스를, `infrastructure/provider/client/` 에 `@HttpExchange` 구현을 둡니다. 구조는 같습니다 — 약속은 `domain`, 구현은 `infrastructure` 입니다.


### 이벤트를 발행하는 서비스는 관리자 재발행 API 를 만듭니다

`OutboxRelay` 는 안전망이지 완전한 보장이 아닙니다. **재시도 횟수가 10 에 이른 건은 조회에서 제외하므로**, 카프카가 오래 멈춰 있었거나 토픽 이름이 어긋난 채로 시간이 지나면 그 행은 `published_at` 이 `NULL` 인 상태로 남고 아무도 다시 보내지 않습니다.

**그 뒤로는 에러도 남지 않습니다.** 실패한 것이 아니라 조회 대상에서 빠진 것이기 때문입니다. `outbox` 테이블에 `retry_count` 와 `last_error` 컬럼을 처음부터 둔 이유가 이 자리이며, **그 값을 보고 다시 보내는 수단이 관리자 API 입니다.** 이것을 만들지 않으면 이벤트가 조용히 유실되는 경로가 열린 채로 배포됩니다.

#### 경로

```
GET  /api/v1/admin/{리소스}/outbox             미발행 건 목록
POST /api/v1/admin/{리소스}/outbox/{id}/retry   한 건 재발행
```

`{리소스}` 는 그 서비스의 관리자 접두사와 같습니다. auth 는 `accounts`, place 는 `places`, report 는 `reports` 입니다.

**두 번째 마디가 어느 서비스인지를 정한다**는 라우팅 규칙을 그대로 따르므로 게이트웨이에 라우트를 새로 열 필요가 없습니다. `/api/v1/admin/places/**` 처럼 이미 그 서비스로 가도록 열려 있기 때문입니다.

#### 재발행할 때 `retry_count` 를 0 으로 되돌립니다

**빠뜨리면 버튼이 아무 일도 하지 않습니다.** `OutboxRelay` 의 조회 조건이 `retry_count < 10` 이므로, 카운터가 10 인 채로 두면 재발행을 눌러도 그 행은 여전히 조회되지 않습니다. 화면에는 성공으로 보이고 이벤트는 계속 나가지 않습니다.

`last_error` 도 함께 비웁니다. 다음에 또 실패하면 그때의 원인이 들어가야 하는데, 예전 값이 남아 있으면 언제 난 오류인지 구분되지 않습니다.

#### 왜 공통 모듈에 두지 않는가

`OutboxMessage` 와 그 레포지터리가 공통 모듈에 있으므로 컨트롤러도 거기 두면 될 것처럼 보입니다. 그렇게 하지 않는 이유는 두 가지입니다.

**공통 모듈에는 관리자 컨트롤러를 두지 않습니다.** 공통 모듈은 기술적 관심사만 담고, 화면과 운영 기능은 각 서비스가 가집니다.

**재발행은 실제로 그 서비스의 운영 기능입니다.** 관리자가 하는 일이 그 서비스 데이터베이스의 행을 고쳐 다시 내보내는 것이므로, 그 데이터를 소유한 쪽이 처리하는 것이 자연스럽습니다.

#### 만드는 서비스와 보호

이벤트를 발행하는 서비스만 해당합니다. config 2계층에 `app.outbox.relay.enabled: true` 가 있는 곳이 기준입니다. 소비만 하는 서비스에는 `outbox` 테이블 자체가 없습니다.

경로가 `/api/v1/admin/**` 이므로 공통 모듈의 보안 체인이 `ADMIN` 역할만 통과시킵니다. **다만 그 서비스가 자기 `SecurityFilterChain` 을 정의하면 그 보호가 물러나므로 직접 넣어야 합니다.**

관리자 화면은 만들지 않습니다. Swagger UI 에서 호출합니다.

#### 재발행된 이벤트는 순서가 어긋나 있을 수 있습니다

멈춘 건은 같은 집합체의 뒤 이벤트를 막지 않습니다. 순서를 지키려고 기다리게 하면 그 집합체의 이벤트가 통째로 멈추기 때문입니다. 따라서 재발행하는 시점에는 **더 나중에 만들어진 이벤트가 이미 나가 있을 수 있습니다.**

지금은 문제가 되지 않습니다. 이벤트 5개가 전부 "네가 가진 것이 낡았다"를 알리는 형태이고, 받는 쪽이 그 시점의 현재 상태를 다시 읽어 가기 때문입니다. **다만 순서 자체가 의미를 갖는 이벤트를 나중에 추가한다면 이 전제가 깨집니다.**

---

## 8. DB가 없는 서비스 (verdict)

verdict, congestion, route 가 여기 해당합니다. 위 형태에서 저장 관련 계층이 통째로 빠집니다.

route 가 여기 들어온 것은 나중입니다. 동물병원이 place 로 편입되면서 소유하던 DB 가 사라졌고, 남은 일이 카카오맵 경로 계산뿐이 되었습니다.

```
com.pawtrail.verdict
│
├── VerdictApplication.java (class)                 @EntityScan과 @EnableJpaRepositories를 쓰지 않습니다.
│                                                   사용하면 JPA가 필수가 되어 기동에 실패합니다.
│                                                   애노테이션과 import를 함께 지웁니다.
│                                                   남는 것은 @SpringBootApplication 한 줄뿐입니다
│
├── presentation/
│   ├── VerdictController.java (class)              /api/v1/places/{id}/verdict — 상세 화면의 판정입니다
│   └── VerdictInternalController.java (class)
│                                                   /internal/verdicts/batch, /summary — 목록과 카운트
│                                                   화면을 위해 search와 user가 호출합니다
│
├── application/
│   ├── service/VerdictService.java (class)         재료를 모으는 층입니다. policy와 pet을 호출해 가져오고
│   │                                               RuleEngine에 넘긴 뒤 응답을 만듭니다
│   └── dto/
│
├── domain/
│   ├── model/
│   │   ├── Verdict.java (record)                   판정 결과의 모양입니다
│   │   └── Reason.java (record)                    항목별 이유와 근거입니다
│   ├── engine/RuleEngine.java (class)              이 서비스의 핵심입니다. f(정책, 프로필) → 판정을
│   │                                               수행합니다. 바깥을 전혀 모르는 순수 함수라 단위 테스트가
│   │                                               쉽고, "8kg + 10kg 이하 → 가능", "안내견 단독 표기 → 불가"
│   │                                               같은 판정 케이스를 테스트로 고정할 수 있습니다
│   ├── exception/VerdictErrorCode.java (enum)
│   └── provider/
│       ├── PolicyData.java (record)                policy에서 받아올 조건 데이터의 모양입니다
│       ├── PolicyProvider.java (interface)         조건을 가져다준다는 약속입니다
│       ├── PetData.java (record)                   pet에서 받아올 프로필 데이터의 모양입니다
│       └── PetProvider.java (interface)            프로필을 가져다준다는 약속입니다
│
├── infrastructure/
│   ├── config/RedisConfig.java (class)             판정 결과 캐시 설정입니다
│   ├── cache/VerdictCacheStore.java (class)        캐시 키를 프로필 전체가 아니라 판정에 쓰이는 값의
│   │                                               조합(체중, 이동장, 유모차, 견종, 접종)으로 잡습니다
│   ├── message/kafka/consumer/
│   │   └── VerdictCacheEvictConsumer.java (class)
│   │                                               policy.changed와 pet.profile.updated를 받아
│   │                                               해당 캐시를 삭제합니다
│   └── provider/client/
│       ├── PolicyClient.java (interface)           @HttpExchange 인터페이스입니다
│       ├── PetClient.java (interface)              @HttpExchange 인터페이스입니다
│       ├── PolicyProviderImpl.java (class)         domain의 PolicyProvider를 구현합니다
│       └── PetProviderImpl.java (class)            domain의 PetProvider를 구현합니다
│
└── src/main/resources/application.yml

없는 것    persistence/                                DB를 사용하지 않습니다
           db/migration/                            테이블이 없습니다
           domain/event/                            이벤트를 발행하지 않고 받기만 합니다
           inbox 사용                                 아래 설명을 참고합니다
```

### 무상태 서비스는 Inbox를 사용하지 않습니다

InboxProcessor는 처리 이력을 DB에 남겨야 하는데 이 서비스들에는 테이블이 없습니다.

다만 문제가 되지 않습니다. 이 서비스들이 이벤트를 받아 수행하는 일이 **캐시 키 삭제**뿐이므로, 같은 이벤트를 여러 번 받아도 결과가 같습니다. 중복 방지 장치가 애초에 필요하지 않은 작업입니다.

---

## 9. 서비스별 형태 분류

도메인 서비스는 14개입니다. 플랫폼 3개를 합쳐 17개입니다.

| 구분 | 서비스 | 소유 DB |
|---|---|---|
| **DB 있음** | auth | auth_db |
| | user | user_db |
| | pet | pet_db |
| | place | place_db (동물병원 포함) |
| | policy | policy_db |
| | search | search_db (검색 색인) |
| | ingest | raw_db |
| | report | report_db (제보) |
| | review | review_db (방문 후기) |
| | notification | notif_db |
| **DB 없음** | verdict | 무상태 순수 계산 |
| | congestion | Redis 캐시만 사용 |
| | route | 카카오맵 경로 계산만 수행합니다 |
| **별도 판단** | extract | 소유 DB 없이 /internal API로만 접근합니다. 다만 Spring Batch가 실행 이력 테이블을 요구하므로 이 부분만 별도로 정합니다 |
| **다른 형태** | gateway / config / eureka | 도메인 서비스가 아니므로 4계층 구조를 따르지 않습니다 |

**report 와 review 를 나눈 이유** — 제보는 쓰기 한 번에 조회는 관리자만 하는 콜드패스이고, 후기는 장소 상세를 열 때마다 조회되며 표시 방식이 자주 바뀝니다. 변경 이유와 부하 성격이 모두 다릅니다.

**route 에 DB 가 없는 이유** — 동물병원이 place 로 편입되어 `vet_db` 가 사라졌습니다. 남은 일은 카카오맵 경로 계산뿐이지만, congestion 과 합치면 카카오맵 장애가 집중률까지 끊으므로 서비스는 분리해 둡니다.

### 이벤트 발행·수신 현황

이벤트는 **5개뿐입니다.** 이 표가 토픽 이름의 단일 참조이므로, 발행하는 쪽과 받는 쪽이 같은 문자열을 쓰는지 여기서 확인합니다.

| 서비스 | outbox (발행) | inbox (수신) |
|---|---|---|
| auth | account.withdrawn | — |
| place | place.updated | — |
| policy | policy.changed | — |
| pet | pet.profile.updated | account.withdrawn |
| report | report.reviewed | account.withdrawn |
| review | — | account.withdrawn |
| user | — | account.withdrawn |
| search | — | place.updated |
| notification | — | policy.changed, report.reviewed, account.withdrawn |
| verdict | — | policy.changed, pet.profile.updated (inbox 미사용) |
| ingest / extract / congestion / route | — | — |

| 이벤트 | 무엇을 알리는가 | payload |
|---|---|---|
| `place.updated` | 장소 정보가 바뀌어 색인이 낡았습니다 | `{placeId}` |
| `policy.changed` | 동반 조건이 바뀌어 알림 대상과 판정 캐시가 낡았습니다 | `{placeId, policyVersion, changedFields[], hasConflict}` |
| `pet.profile.updated` | 반려동물 정보가 바뀌어 판정 캐시가 낡았습니다 | `{petId, accountId, verdictRelevantChanged}` |
| `account.withdrawn` | 탈퇴했으므로 각자 가진 사용자 데이터를 지워야 합니다 | `{accountId}` |
| `report.reviewed` | 제보 처리가 끝나 제보자에게 알려야 합니다 | `{reportId, accountId, status, memo}` |

#### 이벤트로 만들지 않는 것

같은 사실을 전달하더라도 아래에 해당하면 이벤트가 아니라 동기 호출이나 배치로 처리합니다.

| 판단 기준 | 예 → 동기 호출 | 아니오 → 이벤트 |
|---|---|---|
| 호출자가 결과를 기다리는가 | 검색은 판정을 받아야 응답합니다 | 색인이 언제 갱신되든 무방합니다 |
| 응답을 실제로 쓰는가 | verdict 가 policy 조건을 씁니다 | 알림은 발행자가 결과를 보지 않습니다 |
| 실패를 즉시 알아야 하는가 | 저장이 실패하면 청크를 재시도합니다 | 나중에 재시도해도 됩니다 |
| 상대가 죽었으면 실패해야 하는가 | policy 가 죽으면 판정이 불가합니다 | search 가 죽어도 나중에 하면 됩니다 |
| 소비자를 알아야 하는가 | 특정 서비스를 지목해 호출합니다 | 누가 받든 상관없습니다 |

한 줄로 줄이면 **"내가 무엇을 했다"가 아니라 "네가 가진 것이 낡았다"를 알리는 것만 이벤트**입니다.

수집·추출 파이프라인(`ingest → place`, `extract → policy`)을 이벤트로 잇지 않는 이유도 여기에 있습니다. 청크 단위로 실패를 롤백해야 하고, **데이터 갱신은 사람의 승인을 거쳐 실행**하기로 했기 때문입니다. 이벤트로 자동 연쇄시키면 그 승인 단계가 사라집니다.

#### 토픽 이름은 공통 모듈에 두지 않습니다

발행하는 쪽은 `DomainEvent.getTopic()` 이 반환하고, 받는 쪽은 `@KafkaListener(topics = ...)` 에 적습니다. **같은 문자열이 두 레포에 각각 존재합니다.**

공통 모듈에 상수로 두지 않는 이유는, 토픽은 개발 도중 추가·변경·삭제될 수 있는데 공통 모듈에 있으면 그때마다 재배포와 전 서비스 버전업이 필요하기 때문입니다. 공통 모듈의 기준은 "거의 바뀌지 않는 것"입니다.

대신 **문자열이 어긋나면 오류 없이 이벤트만 오지 않습니다.** 위 표를 참조해 정확히 적고, 실물 확인은 Kafka UI(`tools` 프로파일, 9000 포트)에서 토픽에 메시지가 쌓였는지로 합니다.

---

## 10. Spring Boot 4 에서 달라진 것

### 10-1. 애노테이션 패키지 이동

`@EntityScan` 의 패키지가 바뀌었습니다. 옛 경로로 import 하면 `package ... does not exist` 오류가 납니다.

```java
// Spring Boot 3 (이제 존재하지 않습니다)
import org.springframework.boot.autoconfigure.domain.EntityScan;

// Spring Boot 4
import org.springframework.boot.persistence.autoconfigure.EntityScan;
```

### 10-2. 스타터 이름 변경

Spring Boot 4 에서 코드베이스가 모듈 단위로 나뉘면서 스타터 이름도 바뀌었습니다. 옛 이름도 아직 해석되지만 새 이름을 씁니다.

| Spring Boot 3 | Spring Boot 4 |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `org.flywaydb:flyway-core` 직접 추가 | `spring-boot-starter-flyway` |
| `org.springframework.kafka:spring-kafka` | `spring-boot-starter-kafka` |
| micrometer 브리지 + zipkin-reporter 조합 | `spring-boot-starter-zipkin` |
| `spring-boot-starter-test` 하나 | `-webmvc-test`, `-data-jpa-test` 등 모듈별로 분리 |

Flyway 는 스타터로 넣어야 Hibernate 의 스키마 검증보다 먼저 실행됩니다. `flyway-core` 만 직접 넣으면 검증이 먼저 돌아 테이블이 없다는 오류가 납니다.

### 10-3. 설정 프로퍼티 경로 이동

추적 관련 프로퍼티가 옮겨졌습니다. **옛 경로를 써도 오류가 나지 않고 조용히 무시됩니다.** Zipkin 을 띄웠는데 트레이스가 하나도 들어오지 않는다면 이 부분을 확인합니다.

| Spring Boot 3 | Spring Boot 4 |
|---|---|
| `management.zipkin.tracing.endpoint` | `management.tracing.export.zipkin.endpoint` |

### 10-4. 카프카 추적은 따로 켜야 합니다

Spring Boot 4 의 변경점은 아니지만 같은 자리에서 막히므로 함께 적습니다. 스프링 카프카는 Observation 이 **기본으로 꺼져 있습니다.** 켜지 않으면 HTTP 구간까지는 트레이스가 이어지다가 **이벤트를 건너가는 순간 끊깁니다.** 받는 서비스는 새 트레이스를 시작하고, 오류는 나지 않습니다.

```yaml
spring:
  kafka:
    template:
      observation-enabled: true
    listener:
      observation-enabled: true
```

프로듀서만 켜면 헤더는 실려 가지만 컨슈머가 읽지 않아 반쪽입니다. **둘 다 켭니다.** 템플릿의 `application.yml` 에 이미 들어 있습니다.

Outbox 를 쓰므로 원 HTTP 요청과 발행 구간이 항상 이어지지는 않습니다. 커밋 직후 즉시 발행은 다른 스레드에서 일어나고, `OutboxRelay` 가 회수한 건은 스케줄러 스레드라 원 요청과 연결할 방법이 없습니다. Observation 이 보장하는 것은 **발행부터 소비까지**입니다.

### 10-5. Spring Cloud Gateway 아티팩트명 변경

게이트웨이를 다루게 될 때 필요한 내용입니다.

| 이전 | 현재 |
|---|---|
| `spring-cloud-starter-gateway` | `spring-cloud-starter-gateway-server-webflux` |
| `spring-cloud-starter-gateway-mvc` | `spring-cloud-starter-gateway-server-webmvc` |

프로퍼티 접두사도 `spring.cloud.gateway.server.webflux.*` 형태로 바뀌었습니다. **옛 접두사를 쓰면 오류 없이 무시되므로** 설정이 안 먹을 때 이 부분을 확인합니다.
