# 메이커 허브 (maker-hub-android)

라즈베리파이·아두이노·Wokwi·HMD 사이트를 한 앱에서 보는 안드로이드 웹뷰 앱. Kotlin, Compose 없이 View 로만.

## 탭

| 탭 | 첫 화면 | 앱 안에서 열리는 범위 |
|----|---------|----------------------|
| Raspberry Pi | https://www.raspberrypi.com/ | `raspberrypi.com`, `raspberrypi.org` 와 모든 하위 도메인 (forums, datasheets, magpi, id ...) |
| Arduino | https://www.arduino.cc/ | `arduino.cc` 와 모든 하위 도메인 (docs, app, store, login, forum ...) |
| Connect | https://connect.raspberrypi.com/sign-in | 위 라즈베리파이 범위 |
| Wokwi | https://wokwi.com/ | `wokwi.com` 과 하위 도메인 |
| HMD | https://www.hmd.com/en_int/all-phones | `hmd.com` 과 하위 도메인 |
| lmphone | 앱에서 직접 넣은 주소 | 저장한 주소의 호스트 |

lmphone 은 맥에서 도는 [lmphone](../lmphone/README.md)(LM Studio 채팅) 화면이다. cloudflared 빠른 터널 주소가
켤 때마다 바뀌므로 첫 화면을 고정하지 않는다. 탭을 처음 누르면 주소를 물어보고, 넣은 주소를 그 탭의 첫 화면으로
저장한다(`?k=토큰` 까지 그대로). 주소를 바꾸려면 상단 제목을 누르거나 메뉴 > 주소 입력. LAN 주소(`http://192.168...`)
로도 쓸 수 있게 평문 HTTP 를 허용해 뒀다.

탭마다 웹뷰가 따로 살아 있어서 탭을 오가도 페이지·시뮬레이션·원격 셸이 끊기지 않는다.
지금 탭을 한 번 더 누르면 그 사이트 첫 화면으로 간다.

그 밖의 주소(깃허브 저장소, 유튜브, 리셀러 쇼핑몰 등)는 외부 브라우저로 넘긴다.
단 로그인·결제 중간 단계(구글/깃허브/애플/페이스북/마이크로소프트 OAuth, Firebase 인증 핸들러,
PayPal/Stripe)는 앱 안에서 연다. 밖으로 넘기면 로그인 쿠키가 앱으로 안 돌아온다. 규칙은
[UrlPolicy.kt](app/src/main/java/com/hayoonjae/makerhub/UrlPolicy.kt).

## 웹뷰에서 보통 안 되는 것들, 여기서 처리한 것

- **구글 로그인 차단** — 기본 UA 의 `; wv`, `Version/4.0` 을 빼서 일반 크롬 모바일로 보이게 한다.
- **로그인 팝업(window.open)** — 같은 탭 위에 팝업 창을 띄우고 닫기 버튼/뒤로 가기로 닫는다.
  `target="_blank"` 링크는 팝업 대신 현재 화면에서 연다.
- **다운로드** — http(s) 는 DownloadManager, `blob:`(Wokwi zip·펌웨어) 과 `data:` 는 페이지에서 꺼내 저장.
  저장 위치는 `Download/MakerHub/`.
- **파일 업로드** — `<input type=file>` 을 시스템 파일 선택기로 연결 (여러 개 선택 포함).
- **Connect 화면공유 잘림** — Connect(noVNC)는 파이 데스크톱 해상도를 브라우저 영역의 CSS 크기(폰이면 411×914)로
  바꿔 버려서 창이 화면 밖으로 잘린다. [connect_fix.js](app/src/main/assets/connect_fix.js)가 요청 해상도를
  기기 실제 픽셀(예: 1080×2400)로 키운다. 비율이 화면과 같아서 잘리지 않고, 1:1 픽셀이라 흐리지도 않다.
  파이가 해상도 바꾸는 도중이라 거절하면 5초 간격으로 다시 청한다.
- **Connect 두 손가락 확대** — 같은 스크립트가 두 손가락 벌리기/오므리기로 원격 화면을 최대 6배 확대하고, 두 손가락을
  움직이면 화면이 따라 이동한다. 한 손가락 탭·드래그는 원래대로 원격 마우스다. 대신 noVNC 의 두 손가락 원격 스크롤은
  쓸 수 없다(원격 창 스크롤은 스크롤바나 한 손가락 드래그로).
- **전체화면 동영상**, **키보드가 올라오면 하단 탭 숨김**, **회전해도 페이지 유지**, **렌더러 크래시 시 탭 복구**.
- 메뉴: 앞으로 / 첫 화면 / 주소 입력 / 링크 복사 / 공유 / 외부 브라우저 / 데스크톱 사이트(탭별 기억).

## 빌드

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26 (Android 8.0), target/compile 36.

## 한계

- Arduino Cloud 에서 보드에 직접 업로드하는 기능은 PC 용 Arduino Create Agent 가 필요해서 폰에서는 안 된다.
- Wokwi 의 Web Serial/USB 연결은 안드로이드 웹뷰가 지원하지 않는다. 시뮬레이션 자체는 된다.
