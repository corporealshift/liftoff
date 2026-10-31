package com.liftoff.app.data

enum class MissionStatus { DRAFT, ACTIVE, CLOSED }
enum class SortieType { RUN, LIFT }
enum class SortieState { PENDING, PLANNED, IN_FLIGHT, LANDED, SCRUBBED }
enum class FlightPlanSource { GENERATED, REFLY, SIMPLE_RUN }
enum class SetStatus { OPEN, DONE, SKIPPED }
enum class GenerationKind { OUTLINE, FLIGHT_PLAN }
enum class GenerationStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
