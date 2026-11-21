# whereMI

서울 지하철 4호선 탑승 안내 Android 앱.
게이트 통과 후 도착역을 고르면, **다음 역과 남은 시간**을 화면 좌상단에 아주 작게 띄워 둡니다 (유튜브 전체화면 위에도 표시).

- 데이터: 서울 열린데이터광장 실시간 지하철 위치 API (`realtimePosition/4호선`), 도착정보 API로 ETA 보정
- 표시: ① 좌상단 오버레이 알약 (다른 앱 위에 표시 권한) ② 상태바 아이콘에 남은 분 숫자 ③ Android 16 실시간 정보 칩
- 하차 한 정거장 전 / 도착 시 진동 알림
- 빠른 설정 타일 "4호선 탑승"으로 게이트 통과 직후 바로 실행

## 빌드 (CLI)

```bash
./scripts/setup-android-sdk.sh          # 최초 1회: JDK 17, Android SDK, Gradle 설치 (홈 디렉터리)
echo "sdk.dir=$HOME/android-sdk" > local.properties
echo "SEOUL_API_KEY=발급받은키" >> local.properties   # 선택 (앱에서도 입력 가능)
export JAVA_HOME=$(ls -d ~/.local/jdk/jdk-17*)
./gradlew testDebugUnitTest assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```
