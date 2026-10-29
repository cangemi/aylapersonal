package ayla.investment;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Dias úteis do mercado financeiro brasileiro: fins de semana e feriados nacionais. */
final class InvestmentCalendar {
    private static final Map<Integer, Set<LocalDate>> HOLIDAYS = new ConcurrentHashMap<>();
    private InvestmentCalendar() {}

    static boolean businessDay(LocalDate date) {
        var day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays(date.getYear()).contains(date);
    }

    /** Dias úteis em (afterDate, throughDate]; um aporte feito em afterDate só rende a partir do dia seguinte. */
    static int businessDaysAfter(LocalDate afterDate, LocalDate throughDate) {
        int count = 0;
        for (LocalDate d = afterDate.plusDays(1); !d.isAfter(throughDate); d = d.plusDays(1)) if (businessDay(d)) count++;
        return count;
    }

    static LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (!businessDay(d)) d = d.plusDays(1);
        return d;
    }

    static LocalDate previousBusinessDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (!businessDay(d)) d = d.minusDays(1);
        return d;
    }

    static Set<LocalDate> holidays(int year) {
        return HOLIDAYS.computeIfAbsent(year, InvestmentCalendar::compute);
    }

    private static Set<LocalDate> compute(int year) {
        Set<LocalDate> days = new HashSet<>();
        days.add(LocalDate.of(year, 1, 1));
        days.add(LocalDate.of(year, 4, 21));
        days.add(LocalDate.of(year, 5, 1));
        days.add(LocalDate.of(year, 9, 7));
        days.add(LocalDate.of(year, 10, 12));
        days.add(LocalDate.of(year, 11, 2));
        days.add(LocalDate.of(year, 11, 15));
        if (year >= 2024) days.add(LocalDate.of(year, 11, 20));
        days.add(LocalDate.of(year, 12, 25));
        LocalDate easter = easter(year);
        days.add(easter.minusDays(48));
        days.add(easter.minusDays(47));
        days.add(easter.minusDays(2));
        days.add(easter.plusDays(60));
        return Set.copyOf(days);
    }

    static LocalDate easter(int year) {
        int a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4, f = (b + 8) / 25;
        int g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
