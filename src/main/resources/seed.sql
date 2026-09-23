-- FitBook - sample data (providers/trainers, services, and open slots)
-- Loaded on every startup after schema.sql rebuilds the tables.

-- Users: 2 trainers (PROVIDER) + 2 members (CUSTOMER)
-- password_hash values below are placeholders ("changeme") - real BCrypt hashing
-- is added with the auth work in a later milestone.
INSERT INTO users (username, password_hash, role, full_name, email) VALUES
    ('coach_maya',   'changeme', 'PROVIDER', 'Maya Chen',       'maya.chen@fitbook.example'),
    ('coach_diego',  'changeme', 'PROVIDER', 'Diego Alvarez',   'diego.alvarez@fitbook.example'),
    ('member_arjun', 'changeme', 'CUSTOMER', 'Arjun Ravendran', 'member.arjun@fitbook.example'),
    ('member_lena',  'changeme', 'CUSTOMER', 'Lena Park',       'lena.park@fitbook.example');

INSERT INTO providers (user_id, specialty, bio) VALUES
    ((SELECT id FROM users WHERE username = 'coach_maya'),  'Strength & Conditioning', 'Certified strength coach, 8 years training experience.'),
    ((SELECT id FROM users WHERE username = 'coach_diego'), 'Yoga & Mobility',         'RYT-500 yoga instructor focused on mobility and recovery.');

INSERT INTO services (name, duration_minutes, price, description) VALUES
    ('1:1 Personal Training', 60, 75.00, 'One-on-one strength and conditioning session.'),
    ('Group HIIT Class',      45, 25.00, 'High-intensity interval training, small group.'),
    ('Vinyasa Yoga',          60, 30.00, 'Flow-based yoga class for all levels.'),
    ('Mobility & Recovery',   30, 20.00, 'Guided stretching and mobility work.');

-- Availability slots: a spread of OPEN slots across the next few days for each trainer.
INSERT INTO availability_slots (provider_id, service_id, start_time, end_time, status) VALUES
    ((SELECT id FROM providers WHERE specialty = 'Strength & Conditioning'),
     (SELECT id FROM services WHERE name = '1:1 Personal Training'),
     '2026-09-24 09:00', '2026-09-24 10:00', 'OPEN'),
    ((SELECT id FROM providers WHERE specialty = 'Strength & Conditioning'),
     (SELECT id FROM services WHERE name = '1:1 Personal Training'),
     '2026-09-24 11:00', '2026-09-24 12:00', 'OPEN'),
    ((SELECT id FROM providers WHERE specialty = 'Strength & Conditioning'),
     (SELECT id FROM services WHERE name = 'Group HIIT Class'),
     '2026-09-25 17:00', '2026-09-25 17:45', 'OPEN'),
    ((SELECT id FROM providers WHERE specialty = 'Strength & Conditioning'),
     (SELECT id FROM services WHERE name = 'Group HIIT Class'),
     '2026-09-26 17:00', '2026-09-26 17:45', 'BOOKED'),
    ((SELECT id FROM providers WHERE specialty = 'Yoga & Mobility'),
     (SELECT id FROM services WHERE name = 'Vinyasa Yoga'),
     '2026-09-24 08:00', '2026-09-24 09:00', 'OPEN'),
    ((SELECT id FROM providers WHERE specialty = 'Yoga & Mobility'),
     (SELECT id FROM services WHERE name = 'Vinyasa Yoga'),
     '2026-09-25 18:00', '2026-09-25 19:00', 'OPEN'),
    ((SELECT id FROM providers WHERE specialty = 'Yoga & Mobility'),
     (SELECT id FROM services WHERE name = 'Mobility & Recovery'),
     '2026-09-26 08:00', '2026-09-26 08:30', 'OPEN'),
    ((SELECT id FROM providers WHERE specialty = 'Yoga & Mobility'),
     (SELECT id FROM services WHERE name = 'Mobility & Recovery'),
     '2026-09-27 08:00', '2026-09-27 08:30', 'CANCELED');

-- One existing appointment against the BOOKED slot above, to show the
-- relationship end-to-end (the CANCELED slot has no appointment).
INSERT INTO appointments (slot_id, customer_id, service_id, status) VALUES
    ((SELECT id FROM availability_slots WHERE start_time = '2026-09-26 17:00'),
     (SELECT id FROM users WHERE username = 'member_lena'),
     (SELECT id FROM services WHERE name = 'Group HIIT Class'),
     'CONFIRMED');
