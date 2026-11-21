# whereMI

서울 지하철 4호선 탑승 안내 Android 앱.
출발역과 도착역을 고르면, 내가 탈(탄) 열차를 실시간으로 따라가며 **내릴 역까지 몇 역 남았는지**를 화면 오른쪽 위 작은 위젯으로 보여 줍니다. 유튜브 전체화면 위에도 떠 있습니다.

## 동작

- **위젯**: 4호선 색 알약. 2역 이상 남으면 `선바위 | 3역 전`, 도착역이 바로 다음 역이면 흰 진행 바가 차오름
- **탑승 확인**: 탈 열차가 출발역에 곧 도착하면 위젯 배경이 점멸하고, 탭하면 그 열차로 확정 (안 눌러도 열차가 출발하면 자동 판단)
- **알림**: 진행 중 알림(진행 바), 하차 직전 진동 알림, 도착하면 알림·위젯 자동 종료
- **루틴**: 경로 + 요일 + 시간대 + 위치(역 근처 또는 저장한 위치). 그 시간에 그 근처에 들어가면 안내가 자동으로 시작
- **빠른 설정 타일** "4호선 탑승"

## 데이터

- 서울 열린데이터광장 실시간 지하철 API: 위치(`realtimePosition`), 도착정보(`realtimeStationArrival`)
- 실시간 API 는 인증키당 하루 1,000회. 남은 거리에 따라 30초/15초/8초 주기로 조회 (출퇴근 2시간 ≈ 460회)
- API 데이터가 멈추면 역 간 시간표로 열차 위치를 추정

## 필요한 권한

다른 앱 위에 표시(위젯), 알림, 위치 "항상 허용"(루틴 자동 실행 시)

## 빌드 (CLI)

```bash
./scripts/setup-android-sdk.sh          # 최초 1회: JDK 17, Android SDK, Gradle 설치 (홈 디렉터리)
echo "sdk.dir=$HOME/android-sdk" > local.properties
echo "SEOUL_API_KEY=발급받은_실시간_지하철_인증키" >> local.properties   # 커밋되지 않음
export JAVA_HOME=$(ls -d ~/.local/jdk/jdk-17*)
./gradlew testDebugUnitTest assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

인증키는 data.seoul.go.kr 의 **실시간 지하철** 인증키여야 합니다 (일반 인증키는 `ERROR-338`).
