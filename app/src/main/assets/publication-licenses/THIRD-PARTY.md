# Сторонние компоненты

Gradle Wrapper (gradlew, gradlew.bat, gradle/wrapper/) сохраняет исходные уведомления. Лицензия из META-INF/LICENSE оригинального wrapper JAR приложена в licenses/Gradle-Wrapper-LICENSE.txt. Проект Gradle: https://github.com/gradle/gradle .

Android SDK, Android Gradle Plugin и JDK не входят в репозиторий. Их условия не меняются. Зависимости перечислены в build.gradle и app/build.gradle; собственная лицензия Jeronimo701 их не перелицензирует. Уведомления сторонних библиотек в APK сохраняются при стандартной сборке Gradle.

В app/build.gradle этого приложения нет дополнительных runtime Maven-зависимостей. Используются API платформы Android.
