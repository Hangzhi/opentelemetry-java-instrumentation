FROM winamd64/openjdk:11.0.10-jdk-windowsservercore-1809
COPY fake-backend.jar /fake-backend.jar
CMD ["java", "-jar", "/fake-backend.jar"]
