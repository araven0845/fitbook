-- FitBook - sample data (accounts, trainers, services, slots, appointments)
-- Loaded on every startup after schema.sql rebuilds the tables.
-- Slot times are relative to the moment the app starts, so there are always
-- upcoming OPEN slots to book (and one past appointment to show history).

-- Users: 2 trainers (PROVIDER) + 2 members (CUSTOMER).
-- password_hash values are BCrypt hashes of the demo password in the README.
INSERT INTO users (username, password_hash, role, full_name, email) VALUES
    ('coach_maya',   '$2y$10$Nli8tTNhZn8X/KTDxvXKIuXPLH87KcEa.bGimtn4HkTYwmKCaFBoi', 'PROVIDER', 'Maya Chen',       'maya.chen@fitbook.example'),
    ('coach_diego',  '$2y$10$TjtPkGCEdkD5Vdz7J408uOQ5qF9Cr3zHkO665orUwra7YqHdFuXpu', 'PROVIDER', 'Diego Alvarez',   'diego.alvarez@fitbook.example'),
    ('member_arjun', '$2y$10$kh8Q0CHrqz/Z0yP37og9QOQXXoXZaHf4UfpuLWM0akmlIqPhyRRIi', 'CUSTOMER', 'Arjun Ravendran', 'member.arjun@fitbook.example'),
    ('member_lena',  '$2y$10$fvaC.b9m4QPJMWGLaVtYGeTQhdJSFyADb4lZOYN54uuXSt4hDHP6y', 'CUSTOMER', 'Lena Park',       'lena.park@fitbook.example');

INSERT INTO providers (user_id, specialty, bio) VALUES
    ((SELECT id FROM users WHERE username = 'coach_maya'),  'Strength & Conditioning', 'Certified strength coach, 8 years training experience.'),
    ((SELECT id FROM users WHERE username = 'coach_diego'), 'Yoga & Mobility',         'RYT-500 yoga instructor focused on mobility and recovery.');

INSERT INTO services (name, duration_minutes, price, description) VALUES
    ('1:1 Personal Training', 60, 75.00, 'One-on-one strength and conditioning session.'),
    ('Group HIIT Class',      45, 25.00, 'High-intensity interval training, small group.'),
    ('Vinyasa Yoga',          60, 30.00, 'Flow-based yoga class for all levels.'),
    ('Mobility & Recovery',   30, 20.00, 'Guided stretching and mobility work.');

-- Availability slots. day_offset = days from today, start_min = minutes after
-- midnight (540 = 09:00). The end time is start + the service duration.
WITH spec(id, provider_specialty, service_name, day_offset, start_min, status) AS (VALUES
    (1,  'Strength & Conditioning', '1:1 Personal Training', -2,  540, 'BOOKED'),
    (2,  'Strength & Conditioning', '1:1 Personal Training',  1,  540, 'OPEN'),
    (3,  'Strength & Conditioning', '1:1 Personal Training',  1,  660, 'OPEN'),
    (4,  'Strength & Conditioning', 'Group HIIT Class',       2, 1020, 'OPEN'),
    (5,  'Strength & Conditioning', 'Group HIIT Class',       3, 1020, 'BOOKED'),
    (6,  'Strength & Conditioning', '1:1 Personal Training',  5,  600, 'OPEN'),
    (7,  'Yoga & Mobility',         'Vinyasa Yoga',           1,  480, 'OPEN'),
    (8,  'Yoga & Mobility',         'Vinyasa Yoga',           2, 1080, 'OPEN'),
    (9,  'Yoga & Mobility',         'Mobility & Recovery',    3,  480, 'OPEN'),
    (10, 'Yoga & Mobility',         'Mobility & Recovery',    4,  480, 'CANCELLED'),
    (11, 'Yoga & Mobility',         'Vinyasa Yoga',           5, 1080, 'OPEN'))
INSERT INTO availability_slots (id, provider_id, service_id, start_time, end_time, status)
SELECT sp.id, p.id, sv.id,
       strftime('%Y-%m-%d %H:%M', 'now', 'localtime', printf('%+d days', sp.day_offset), 'start of day', printf('+%d minutes', sp.start_min)),
       strftime('%Y-%m-%d %H:%M', 'now', 'localtime', printf('%+d days', sp.day_offset), 'start of day', printf('+%d minutes', sp.start_min + sv.duration_minutes)),
       sp.status
FROM spec sp
JOIN providers p ON p.specialty = sp.provider_specialty
JOIN services sv ON sv.name = sp.service_name;

-- Slot 1 is in the past (reads back as COMPLETED), slot 5 is an upcoming
-- booking by Lena, slot 6 is open again after Arjun cancelled his booking.
INSERT INTO appointments (slot_id, customer_id, service_id, status) VALUES
    (1, (SELECT id FROM users WHERE username = 'member_arjun'), (SELECT id FROM services WHERE name = '1:1 Personal Training'), 'BOOKED'),
    (5, (SELECT id FROM users WHERE username = 'member_lena'),  (SELECT id FROM services WHERE name = 'Group HIIT Class'),      'BOOKED'),
    (6, (SELECT id FROM users WHERE username = 'member_arjun'), (SELECT id FROM services WHERE name = '1:1 Personal Training'), 'CANCELLED');
