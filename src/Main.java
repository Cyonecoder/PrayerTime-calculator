import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static java.lang.Math.*;

public class Main {
    public static void main(String[] args) {
        int Y = 2024; // Year
        int M = 3;// Month
        int D = 17; //day
        int H = 23; //hour
        int m = 59; //Minute
        int s = 59; //Second
        int Z = 0; // UTC offset
        int SF = 1;// Shadow Length
        double LONG =-6.7480954;//-7.603869;// longitude CASA
        double ELEVATION =  96;//60;// Elevation from sea CASA
        double LAT =34.0361152;//33.589886;// Latitude CASA
        double FAJR_ANGLE = 18.5; // fajr angle for calculating fajr timing
        double ISHA_ANGLE = 17; // isha angle to be changed later um al qura algo need 90 min diff between maghrib and isha thats dosent work correctly for Morocco

        // add junary and february as 13 14 months for caculating date in gregorian calendar
        int A = Y / 100;
        int B = 2 + A / 4 - A;

        //julian date calculation
        double JD = 1720994.5 + (int) (365.25 * Y) + (int) (30.6001 * (M + 1)) + B + D +
                ((H * 3600 + m * 60 + s) / 86400.0) - (Z / 24.0);



        double T = 2 * Math.PI * (JD - 2451545) / 365.25; // Time parameter
        //The Sun's declination is the angle between its rays and the Earth's equatorial plane, changing yearly due to Earth's tilt.
        // Calculate DELTA
        double DELTA = 0.37877 +
                23.264 * Math.sin(Math.toRadians(57.297 * T - 79.547)) +
                0.3812 * Math.sin(Math.toRadians(2 * 57.297 * T - 82.682)) +
                0.17132 * Math.sin(Math.toRadians(3 * 57.297 * T - 59.722));


       // Calculate the time parameter
        double U = (JD - 2451545.0) / 36525.0;
       // Calculate the mean longitude of the Sun
        double L0 = 280.46607 + 36000.7698 * U;
        // Calculate the equation of time correction in seconds
        double ET1000 = -(1789 + 237 * U) * Math.sin(Math.toRadians(L0)) -
                (7146 - 62 * U) * Math.cos(Math.toRadians(L0)) +
                (9934 - 14 * U) * Math.sin(Math.toRadians(2 * L0)) -
                (29 + 5 * U) * Math.cos(Math.toRadians(2 * L0)) +
                (74 + 10 * U) * Math.sin(Math.toRadians(3 * L0)) +
                (320 - 4 * U) * Math.cos(Math.toRadians(3 * L0)) - 212 * Math.sin(Math.toRadians(4 * L0));
// Convert the equation of time correction to minutes
        double ET = ET1000 / 1000.0;

        
       //the moment when the Sun reaches its highest position in the sky
        double TT = 12 + Z - (LONG / 15) - (ET / 60);


        double SA_FAJR = -FAJR_ANGLE;
        double SA_SUNRISE
                = -0.8333 - (0.0347 * sqrt(ELEVATION));

        double SA_MAGHRIB = SA_SUNRISE;
        double delta = DELTA; // Use the calculated DELTA value
        double lat = LAT; // Use the provided LAT value

        double sf = 1.0;
        double x = Math.abs(delta - lat);
        double angleRadians = Math.toRadians(x);
        double tangentValue = Math.tan(angleRadians);

        double sa_asr = Math.toDegrees(acot(sf + Math.tan(Math.toRadians(Math.abs(DELTA - LAT)))));
//        System.out.println("SA_ASR: " + sa_asr);

        double COS_AL_FAJR = (Math.sin(Math.toRadians(SA_FAJR)) - Math.sin(Math.toRadians(LAT)) * Math.sin(Math.toRadians(DELTA))) / (Math.cos(Math.toRadians(LAT)) * Math.cos(Math.toRadians(DELTA)));

        // Compute the arc cosine to get the final result


        double AL_FAJR = Math.toDegrees(Math.acos(COS_AL_FAJR));


        double FAJR = (TT - AL_FAJR / 15);

        // Convert fractional part to hours, minutes, and seconds
        int hours = (int) FAJR;
        int minutes = (int) ((FAJR - hours) * 60);
        int seconds = (int) (((FAJR - hours) * 60 - minutes) * 60);

        // Format FAJR time as a string
        String fajrTime = String.format("%02d:%02d:%02d", hours, minutes, seconds);



        double COS_AL_ASR = (Math.sin(Math.toRadians(sa_asr)) - Math.sin(Math.toRadians(LAT)) * Math.sin(Math.toRadians(DELTA))) / (Math.cos(Math.toRadians(LAT)) * Math.cos(Math.toRadians(DELTA)));

        double AL_ASR = Math.toDegrees(Math.acos(COS_AL_ASR));
//        System.out.println("AL_ASR"+AL_ASR);

        double ASR= TT + AL_ASR / 15;
        
        int hoursASR = (int) ASR;
        
        int minutesASR = (int) ((ASR - hoursASR) * 60);
        
        int secondsASR = (int) (((ASR - hoursASR) * 60 - minutesASR) * 60);

        String asrTime = String.format("%02d:%02d:%02d", hoursASR, minutesASR, secondsASR);


/////////////////////
        double COS_AL_MAGHRIB= (Math.sin(Math.toRadians(SA_SUNRISE)) - Math.sin(Math.toRadians(LAT)) * Math.sin(Math.toRadians(DELTA))) / (Math.cos(Math.toRadians(LAT)) * Math.cos(Math.toRadians(DELTA)));
        double COS_HA_SUNRISE =  COS_AL_MAGHRIB;
        double AL_MAGHRIB=Math.toDegrees(Math.acos(COS_AL_MAGHRIB));
        double HA_SUNRISE = AL_MAGHRIB;

        double SUNRISE = TT - HA_SUNRISE / 15;

        
        int hoursSunRize = (int) SUNRISE;
        
        int minutesSunRize = (int) ((SUNRISE - hoursSunRize) * 60);
        int secondsSunRize = (int) (((SUNRISE - hoursSunRize) * 60 - minutesSunRize) * 60);
        String sunRizeTime = String.format("%02d:%02d:%02d", hoursSunRize, minutesSunRize, secondsSunRize);

/////////////////////////

        double DUHR = TT + (6.0 / 60.0); // Ensure floating-point division

//        System.out.println("TT " + TT);
//        System.out.println("DUHR " + DUHR);

// Convert DUHR time to hours, minutes, and seconds
        int hoursDUHR = (int) DUHR; // Extract the integer part for hours
        int minutesDUHR = (int) ((DUHR - hoursDUHR) * 60);

        int secondsDUHR = (int) ((DUHR - hoursDUHR - minutesDUHR / 60.0) * 3600); // Extract the seconds

// Format DUHR time as a string
        String sunDUHR = String.format("%02d:%02d:%02d", hoursDUHR, minutesDUHR, secondsDUHR);



        double MAGHRIB = TT + (AL_MAGHRIB / 15.0)+(3 + 30.0 / 60) / 60;
//        System.out.println("DUHR " + DUHR);

// Convert DUHR time to hours, minutes, and seconds
        int hoursMAGHRIB = (int) MAGHRIB; // Extract the integer part for hours
        int minutesMAGHRIB = (int) ((MAGHRIB - hoursMAGHRIB) * 60); // Extract the minutes
        int secondsMAGHRIB = (int) ((MAGHRIB - hoursMAGHRIB - minutesMAGHRIB / 60.0) * 3600); // Extract the seconds

// Format Maghrib time as a string
        String sunMAGHRIB= String.format("%02d:%02d:%02d", hoursMAGHRIB, minutesMAGHRIB, secondsMAGHRIB);
///////////////////////////

        double SA_ISHA = - ISHA_ANGLE ;

      double  COS_Al_ISHA = (Math.sin(Math.toRadians(SA_ISHA)) - Math.sin(Math.toRadians(LAT)) * Math.sin(Math.toRadians(DELTA))) / (Math.cos(Math.toRadians(LAT)) * Math.cos(Math.toRadians(DELTA)));

      double AL_ISHA =Math.toDegrees(Math.acos(COS_Al_ISHA));

      double ISHA = TT + AL_ISHA / 15;
      // Convert Al ISHA time to hours, minutes, and seconds
        int hoursISHA = (int) ISHA; // Extract the integer part for hours
        int minutesISHA = (int) ((ISHA - hoursISHA) * 60); // Extract the minutes
        int secondsISHA = (int) ((ISHA - hoursISHA - minutesISHA / 60.0) * 3600); // Extract the seconds
// Format Isha time as a string
        String theISHA= String.format("%02d:%02d:%02d", hoursISHA, minutesISHA, secondsISHA);
        ///////////////////////

        // Output Al FAJR time
        System.out.println("AL FAJR     "+D+"/"+M+"/"+Y+" ==> " + fajrTime);
        System.out.println("SunRize     "+D+"/"+M+"/"+Y+" ==> " + sunRizeTime);
        // Output al DUHR time
        System.out.println("AL DUHR     " + D + "/" + M + "/" + Y + " ==> " + sunDUHR);
        //OutPut Al ASR time
        System.out.println("AL Asr      "+D+"/"+M+"/"+Y+" ==> " + asrTime);
        // Output al MAGHRIB time
        System.out.println("AL MAGHRIB  " + D + "/" + M + "/" + Y + " ==> " + sunMAGHRIB);

       //OutPut ISHA
        System.out.println("AL ISHA     "+D+"/"+M+"/"+Y+" ==> " + theISHA);

    }

    public static double acot(double x) {
        return Math.atan(1 / x);
    }
}