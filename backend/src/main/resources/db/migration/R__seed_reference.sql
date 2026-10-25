-- STORY-004 reference data: the default category set, copied into each household.
-- Repeatable and idempotent: re-running adds categories that are missing (matched by reference_key) and never
-- touches rows a household has renamed, archived or re-ordered. New households call seed_household_categories().

CREATE OR REPLACE FUNCTION seed_household_categories(target_household uuid) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  -- Top-level categories.
  INSERT INTO category (id, household_id, parent_id, reference_key, name_en, name_ar, icon, color, is_essential, sort_order)
  SELECT gen_random_uuid(), target_household, NULL, r.key, r.name_en, r.name_ar, r.icon, r.color, r.essential, r.sort_order
  FROM (VALUES
    ('groceries',     'Groceries',        'بقالة',            'shopping-basket', '#16A34A', true,   10),
    ('dining',        'Dining out',       'مطاعم',            'utensils',        '#F97316', false,  20),
    ('car',           'Car',              'السيارة',          'car',             '#F59E0B', true,   30),
    ('transport',     'Transport',        'مواصلات',          'bus',             '#0EA5E9', true,   40),
    ('bills',         'Bills',            'فواتير',           'receipt',         '#6366F1', true,   50),
    ('rent',          'Rent & housing',   'إيجار وسكن',       'home',            '#8B5CF6', true,   60),
    ('health',        'Health',           'صحة',              'heart-pulse',     '#EF4444', true,   70),
    ('education',     'Education',        'تعليم',            'graduation-cap',  '#14B8A6', true,   80),
    ('kids',          'Kids',             'أطفال',            'baby',            '#EC4899', false,  90),
    ('clothing',      'Clothing',         'ملابس',            'shirt',           '#A855F7', false, 100),
    ('personal-care', 'Personal care',    'عناية شخصية',      'sparkles',        '#F472B6', false, 110),
    ('household',     'Household items',  'مستلزمات المنزل',  'sofa',            '#84CC16', false, 120),
    ('electronics',   'Electronics',      'إلكترونيات',       'smartphone',      '#64748B', false, 130),
    ('entertainment', 'Entertainment',    'ترفيه',            'film',            '#D946EF', false, 140),
    ('subscriptions', 'Subscriptions',    'اشتراكات',         'repeat',          '#06B6D4', false, 150),
    ('travel',        'Travel',           'سفر',              'plane',           '#0284C7', false, 160),
    ('gifts',         'Gifts & charity',  'هدايا وصدقات',     'gift',            '#E11D48', false, 170),
    ('fees',          'Fees & charges',   'رسوم ومصاريف بنكية','landmark',       '#78716C', false, 180),
    ('pets',          'Pets',             'حيوانات أليفة',    'paw-print',       '#CA8A04', false, 190),
    ('other',         'Other',            'أخرى',             'circle-ellipsis', '#9CA3AF', false, 900)
  ) AS r(key, name_en, name_ar, icon, color, essential, sort_order)
  ON CONFLICT (household_id, reference_key) WHERE reference_key IS NOT NULL DO NOTHING;

  -- Subcategories (parent resolved by reference_key within the same household).
  INSERT INTO category (id, household_id, parent_id, reference_key, name_en, name_ar, icon, color, is_essential, sort_order)
  SELECT gen_random_uuid(), target_household, p.id, s.key, s.name_en, s.name_ar, s.icon, p.color, p.is_essential, s.sort_order
  FROM (VALUES
    ('car.fuel',        'car',   'Fuel',            'بنزين',        'fuel',      1),
    ('car.maintenance', 'car',   'Maintenance',     'صيانة',        'wrench',    2),
    ('car.parking',     'car',   'Parking & tolls', 'انتظار وبوابات','parking-circle', 3),
    ('car.insurance',   'car',   'Insurance',       'تأمين',        'shield',    4),
    ('bills.electricity','bills','Electricity',     'كهرباء',       'zap',       1),
    ('bills.water',     'bills', 'Water',           'مياه',         'droplet',   2),
    ('bills.gas',       'bills', 'Gas',             'غاز',          'flame',     3),
    ('bills.internet',  'bills', 'Internet',        'إنترنت',       'wifi',      4),
    ('bills.mobile',    'bills', 'Mobile',          'موبايل',       'phone',     5)
  ) AS s(key, parent_key, name_en, name_ar, icon, sort_order)
  JOIN category p ON p.household_id = target_household AND p.reference_key = s.parent_key
  ON CONFLICT (household_id, reference_key) WHERE reference_key IS NOT NULL DO NOTHING;
END;
$$;

-- Copy into every household that exists when this runs (including the V1-era one).
SELECT seed_household_categories(id) FROM household;
