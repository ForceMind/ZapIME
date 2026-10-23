// SPDX-License-Identifier: GPL-3.0-only
//! Process-local scheduling policy. No timer, HTTP, credentials, or queue mutation.
//!
//! Hosts feed monotonic elapsed time and persisted-data events, then run at most
//! one returned attempt on their background worker. Reconstruct pending work
//! from Store on restart; never interpret this policy as a durable queue.
use std::time::Duration;

const DEBOUNCE: Duration = Duration::from_secs(2);
const MAX_WAIT: Duration = Duration::from_secs(30);
const MIN_PULL_GAP: Duration = Duration::from_secs(5);
const MIN_POLL: Duration = Duration::from_secs(60);
const MAX_POLL: Duration = Duration::from_secs(300);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScheduleError {
    ClockMovedBackwards,
    UnexpectedCompletion,
    InvalidOutcome,
    TicketExhausted,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ticket(u64);
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Attempt {
    pub ticket: Ticket,
    pub pull: bool,
    pub push: bool,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Success {
    /// Some only when this attempt requested a pull. True if changes applied.
    pub pulled_changes: Option<bool>,
    /// Read from the durable queue after upload acknowledgements are committed.
    pub pending_uploads: bool,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Failure {
    Transient,
    Unauthorized,
    /// A parsed, validated Retry-After duration; may exceed the normal backoff.
    RateLimited(Duration),
}
#[derive(Clone, Copy)]
struct Dirty {
    first: Duration,
    last: Duration,
}
impl Dirty {
    fn due(self) -> Duration {
        self.last
            .saturating_add(DEBOUNCE)
            .min(self.first.saturating_add(MAX_WAIT))
    }
}
struct Flight {
    attempt: Attempt,
    dirty: Option<Dirty>,
    manual: bool,
}

/// Disabled by default. Each profile owns a separate schedule.
pub struct Schedule {
    clock: Duration,
    enabled: bool,
    online: bool,
    foreground: bool,
    authorized: bool,
    dirty: Option<Dirty>,
    manual: bool,
    pull_due: Duration,
    last_pull: Option<Duration>,
    poll_interval: Duration,
    retry_after: Duration,
    failures: u32,
    next_ticket: u64,
    flight: Option<Flight>,
}
impl Default for Schedule {
    fn default() -> Self {
        Self {
            clock: Duration::ZERO,
            enabled: false,
            online: false,
            foreground: false,
            authorized: false,
            dirty: None,
            manual: false,
            pull_due: Duration::ZERO,
            last_pull: None,
            poll_interval: MIN_POLL,
            retry_after: Duration::ZERO,
            failures: 0,
            next_ticket: 0,
            flight: None,
        }
    }
}
impl Schedule {
    fn tick(&mut self, now: Duration) -> Result<(), ScheduleError> {
        if now < self.clock {
            return Err(ScheduleError::ClockMovedBackwards);
        }
        self.clock = now;
        Ok(())
    }
    /// Host permission/configuration gates; no credentials belong in this type.
    /// An in-flight attempt still needs completion after host cancellation.
    pub fn configure(
        &mut self,
        now: Duration,
        enabled: bool,
        online: bool,
        foreground: bool,
    ) -> Result<(), ScheduleError> {
        self.tick(now)?;
        if foreground && !self.foreground {
            let refresh = self
                .last_pull
                .map(|last| last.saturating_add(MIN_PULL_GAP).max(now))
                .unwrap_or(now);
            self.pull_due = self.pull_due.min(refresh);
        }
        self.enabled = enabled;
        self.online = online;
        self.foreground = foreground;
        Ok(())
    }
    /// Explicit credential setup/renewal. A 401/403 never resets itself on a timer.
    pub fn authorize(&mut self, now: Duration) -> Result<(), ScheduleError> {
        self.tick(now)?;
        self.authorized = true;
        Ok(())
    }
    /// Call after a committed edit or after discovering pending work at startup.
    /// Does not coalesce, rewrite or acknowledge immutable Store operations.
    pub fn local_change(&mut self, now: Duration) -> Result<(), ScheduleError> {
        self.tick(now)?;
        let first = self.dirty.map(|dirty| dirty.first).unwrap_or(now);
        self.dirty = Some(Dirty { first, last: now });
        Ok(())
    }
    /// Explicit user sync/send: immediate pull and any known pending uploads,
    /// still respecting permission, connectivity, authorization and Retry-After.
    pub fn manual(&mut self, now: Duration) -> Result<(), ScheduleError> {
        self.tick(now)?;
        if self
            .flight
            .as_ref()
            .is_some_and(|flight| flight.attempt.pull)
        {
            return Ok(());
        }
        self.manual = true;
        Ok(())
    }
    /// Opening a cross-device panel does not wait for this optional refresh.
    pub fn open_panel(&mut self, now: Duration) -> Result<(), ScheduleError> {
        self.tick(now)?;
        if self
            .last_pull
            .is_none_or(|last| now.saturating_sub(last) >= MIN_PULL_GAP)
        {
            self.pull_due = self.pull_due.min(now);
        }
        Ok(())
    }
    /// Absolute elapsed-time deadline. None means no timer should be armed.
    pub fn next_wake(&mut self, now: Duration) -> Result<Option<Duration>, ScheduleError> {
        self.tick(now)?;
        if !self.enabled
            || !self.online
            || !self.foreground
            || !self.authorized
            || self.flight.is_some()
        {
            return Ok(None);
        }
        let mut due = self.pull_due;
        if let Some(dirty) = self.dirty {
            due = due.min(dirty.due());
        }
        if self.manual {
            due = now;
        }
        Ok(Some(due.max(self.retry_after).max(now)))
    }
    /// Multiple timer/panel/edit triggers share the existing in-flight attempt.
    pub fn poll(&mut self, now: Duration) -> Result<Option<Attempt>, ScheduleError> {
        if self.next_wake(now)?.is_none_or(|due| due > now) {
            return Ok(None);
        }
        let id = self
            .next_ticket
            .checked_add(1)
            .ok_or(ScheduleError::TicketExhausted)?;
        let push = self
            .dirty
            .is_some_and(|dirty| self.manual || dirty.due() <= now);
        let attempt = Attempt {
            ticket: Ticket(id),
            pull: self.manual || self.pull_due <= now,
            push,
        };
        self.next_ticket = id;
        self.flight = Some(Flight {
            attempt,
            dirty: if push { self.dirty.take() } else { None },
            manual: std::mem::take(&mut self.manual),
        });
        Ok(Some(attempt))
    }
    /// The transport must authenticate and atomically apply pulled changes before
    /// reporting success. This method never advances Store's pull cursor.
    /// jitter is host-provided entropy; bounded to 0..999 here for deterministic tests.
    pub fn complete(
        &mut self,
        now: Duration,
        ticket: Ticket,
        result: Result<Success, Failure>,
        jitter: u16,
    ) -> Result<(), ScheduleError> {
        self.tick(now)?;
        let flight = self
            .flight
            .as_ref()
            .filter(|flight| flight.attempt.ticket == ticket)
            .ok_or(ScheduleError::UnexpectedCompletion)?;
        if let Ok(success) = result {
            if success.pulled_changes.is_some() != flight.attempt.pull {
                return Err(ScheduleError::InvalidOutcome);
            }
        }
        let flight = self.flight.take().expect("validated active ticket");
        match result {
            Ok(success) => {
                self.failures = 0;
                self.retry_after = Duration::ZERO;
                if let Some(changed) = success.pulled_changes {
                    self.poll_interval = if changed || self.last_pull.is_none() {
                        MIN_POLL
                    } else {
                        self.poll_interval.saturating_mul(2).min(MAX_POLL)
                    };
                    self.last_pull = Some(now);
                    self.pull_due = now.saturating_add(self.poll_interval);
                }
                if success.pending_uploads && self.dirty.is_none() {
                    self.dirty = Some(Dirty {
                        first: now,
                        last: now,
                    });
                }
            }
            Err(error) => {
                if let Some(previous) = flight.dirty {
                    self.dirty = Some(match self.dirty {
                        Some(current) => Dirty {
                            first: previous.first.min(current.first),
                            last: previous.last.max(current.last),
                        },
                        None => previous,
                    });
                }
                self.manual |= flight.manual;
                self.failures = self.failures.saturating_add(1).min(9);
                let base_ms = (2000u64 << (self.failures - 1)).min(300_000);
                let delay = Duration::from_millis(
                    (base_ms + base_ms * u64::from(jitter.min(999)) / 5000).min(300_000),
                );
                let delay = match error {
                    Failure::Unauthorized => {
                        self.authorized = false;
                        delay
                    }
                    Failure::RateLimited(retry) => delay.max(retry),
                    Failure::Transient => delay,
                };
                self.retry_after = now.saturating_add(delay);
            }
        }
        Ok(())
    }
}
