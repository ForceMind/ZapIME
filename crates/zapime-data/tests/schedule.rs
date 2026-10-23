// SPDX-License-Identifier: GPL-3.0-only
use std::time::Duration;
use zapime_data::schedule::{Attempt, Failure, Schedule, ScheduleError, Success};
fn sec(n: u64) -> Duration {
    Duration::from_secs(n)
}
fn online() -> Schedule {
    let mut schedule = Schedule::default();
    schedule.configure(sec(0), true, true, true).unwrap();
    schedule.authorize(sec(0)).unwrap();
    schedule
}
fn done(schedule: &mut Schedule, now: u64, attempt: Attempt, changed: bool, pending: bool) {
    schedule
        .complete(
            sec(now),
            attempt.ticket,
            Ok(Success {
                pulled_changes: attempt.pull.then_some(changed),
                pending_uploads: pending,
            }),
            0,
        )
        .unwrap();
}
fn initial(schedule: &mut Schedule) {
    let attempt = schedule.poll(sec(0)).unwrap().unwrap();
    assert!(attempt.pull && !attempt.push);
    done(schedule, 0, attempt, false, false);
}

#[test]
fn disabled_offline_background_and_unauthorized_never_arm_timers() {
    let mut s = Schedule::default();
    s.local_change(sec(0)).unwrap();
    s.manual(sec(0)).unwrap();
    assert_eq!(s.next_wake(sec(0)), Ok(None));
    s.configure(sec(0), true, true, true).unwrap();
    assert_eq!(s.poll(sec(0)), Ok(None));
    s.authorize(sec(0)).unwrap();
    s.configure(sec(0), true, false, true).unwrap();
    assert_eq!(s.poll(sec(50)), Ok(None));
    s.configure(sec(50), true, true, false).unwrap();
    assert_eq!(s.next_wake(sec(100)), Ok(None));
    s.configure(sec(100), false, true, true).unwrap();
    assert_eq!(s.poll(sec(100)), Ok(None));
    s.configure(sec(100), true, true, true).unwrap();
    let attempt = s.poll(sec(100)).unwrap().unwrap();
    assert!(attempt.pull && attempt.push);
}

#[test]
fn local_edits_debounce_and_continuous_edits_flush_within_thirty_seconds() {
    let mut s = online();
    initial(&mut s);
    s.local_change(sec(1)).unwrap();
    s.local_change(sec(2)).unwrap();
    assert_eq!(s.next_wake(sec(2)), Ok(Some(sec(4))));
    assert_eq!(s.poll(sec(3)), Ok(None));
    let attempt = s.poll(sec(4)).unwrap().unwrap();
    assert!(!attempt.pull && attempt.push);
    done(&mut s, 4, attempt, false, false);
    for t in 10..=39 {
        s.local_change(sec(t)).unwrap();
        assert_eq!(s.poll(sec(t)), Ok(None));
    }
    assert_eq!(s.next_wake(sec(39)), Ok(Some(sec(40))));
    let attempt = s.poll(sec(40)).unwrap().unwrap();
    assert!(attempt.push && !attempt.pull);
}

#[test]
fn one_flight_shares_triggers_and_preserves_edits_during_upload() {
    let mut s = online();
    s.local_change(sec(0)).unwrap();
    s.manual(sec(0)).unwrap();
    let first = s.poll(sec(0)).unwrap().unwrap();
    assert!(first.pull && first.push);
    for t in 1..=5 {
        s.manual(sec(t)).unwrap();
        s.open_panel(sec(t)).unwrap();
        assert_eq!(s.poll(sec(t)), Ok(None));
        assert_eq!(s.next_wake(sec(t)), Ok(None));
    }
    s.local_change(sec(5)).unwrap();
    done(&mut s, 5, first, false, true);
    assert_eq!(s.poll(sec(6)), Ok(None));
    let next = s.poll(sec(7)).unwrap().unwrap();
    assert!(next.push && !next.pull);
    done(&mut s, 7, next, false, false);
    assert_eq!(s.next_wake(sec(7)), Ok(Some(sec(65))));
}

#[test]
fn empty_polls_back_off_to_five_minutes_and_changes_reset_to_one_minute() {
    let mut s = online();
    initial(&mut s);
    for (now, next) in [(60, 180), (180, 420), (420, 720), (720, 1020)] {
        let attempt = s.poll(sec(now)).unwrap().unwrap();
        assert!(attempt.pull && !attempt.push);
        done(&mut s, now, attempt, false, false);
        assert_eq!(s.next_wake(sec(now)), Ok(Some(sec(next))));
    }
    let attempt = s.poll(sec(1020)).unwrap().unwrap();
    done(&mut s, 1020, attempt, true, false);
    assert_eq!(s.next_wake(sec(1020)), Ok(Some(sec(1080))));
}

#[test]
fn panel_refresh_cooldown_foreground_resume_and_manual_refresh() {
    let mut s = online();
    initial(&mut s);
    s.open_panel(sec(4)).unwrap();
    assert_eq!(s.next_wake(sec(4)), Ok(Some(sec(60))));
    s.open_panel(sec(5)).unwrap();
    let attempt = s.poll(sec(5)).unwrap().unwrap();
    done(&mut s, 5, attempt, false, false);
    s.configure(sec(6), true, true, false).unwrap();
    s.configure(sec(7), true, true, true).unwrap();
    assert_eq!(s.next_wake(sec(7)), Ok(Some(sec(10))));
    s.manual(sec(7)).unwrap();
    let attempt = s.poll(sec(7)).unwrap().unwrap();
    assert!(attempt.pull);
    done(&mut s, 7, attempt, false, false);
    s.configure(sec(8), true, true, false).unwrap();
    assert_eq!(s.poll(sec(1000)), Ok(None));
    s.configure(sec(1000), true, true, true).unwrap();
    assert!(s.poll(sec(1000)).unwrap().unwrap().pull);
}

#[test]
fn failures_keep_work_exponential_jitter_is_bounded_and_manual_does_not_bypass() {
    let mut s = online();
    s.local_change(sec(0)).unwrap();
    s.manual(sec(0)).unwrap();
    let mut now = Duration::ZERO;
    for expected_ms in [
        2399, 4799, 9598, 19196, 38393, 76787, 153574, 300000, 300000,
    ] {
        let attempt = s.poll(now).unwrap().unwrap();
        assert!(attempt.pull && attempt.push);
        s.complete(now, attempt.ticket, Err(Failure::Transient), 999)
            .unwrap();
        let wake = s.next_wake(now).unwrap().unwrap();
        assert_eq!(wake - now, Duration::from_millis(expected_ms));
        s.manual(now).unwrap();
        assert_eq!(s.poll(now), Ok(None));
        now = wake;
    }
    let attempt = s.poll(now).unwrap().unwrap();
    s.complete(
        now,
        attempt.ticket,
        Ok(Success {
            pulled_changes: Some(true),
            pending_uploads: false,
        }),
        0,
    )
    .unwrap();
    s.local_change(now).unwrap();
    assert_eq!(s.next_wake(now), Ok(Some(now + sec(2))));
}

#[test]
fn rate_limit_is_respected_across_connectivity_and_authorization_changes() {
    let mut s = online();
    let first = s.poll(sec(0)).unwrap().unwrap();
    s.complete(sec(0), first.ticket, Err(Failure::RateLimited(sec(900))), 0)
        .unwrap();
    s.manual(sec(1)).unwrap();
    s.open_panel(sec(1)).unwrap();
    s.configure(sec(1), true, false, true).unwrap();
    s.configure(sec(2), true, true, true).unwrap();
    s.authorize(sec(2)).unwrap();
    assert_eq!(s.next_wake(sec(2)), Ok(Some(sec(900))));
    assert_eq!(s.poll(sec(899)), Ok(None));
    let next = s.poll(sec(900)).unwrap().unwrap();
    s.complete(sec(900), next.ticket, Err(Failure::Unauthorized), 0)
        .unwrap();
    assert_eq!(s.next_wake(sec(901)), Ok(None));
    s.manual(sec(1500)).unwrap();
    assert_eq!(s.poll(sec(1500)), Ok(None));
    s.authorize(sec(1500)).unwrap();
    assert!(s.poll(sec(1500)).unwrap().is_some());
}

#[test]
fn clock_and_completion_validation_keep_the_current_flight() {
    let mut s = online();
    initial(&mut s);
    s.manual(sec(1)).unwrap();
    let first = s.poll(sec(1)).unwrap().unwrap();
    assert_eq!(
        s.complete(
            sec(1),
            first.ticket,
            Ok(Success {
                pulled_changes: None,
                pending_uploads: false
            }),
            0
        ),
        Err(ScheduleError::InvalidOutcome)
    );
    assert_eq!(s.poll(sec(1)), Ok(None));
    done(&mut s, 1, first, false, false);
    assert_eq!(
        s.complete(sec(1), first.ticket, Err(Failure::Transient), 0),
        Err(ScheduleError::UnexpectedCompletion)
    );
    assert_eq!(
        s.local_change(sec(0)),
        Err(ScheduleError::ClockMovedBackwards)
    );
    s.manual(sec(2)).unwrap();
    let second = s.poll(sec(2)).unwrap().unwrap();
    assert_eq!(
        s.complete(sec(2), first.ticket, Err(Failure::Transient), 0),
        Err(ScheduleError::UnexpectedCompletion)
    );
    done(&mut s, 2, second, false, false);
}
