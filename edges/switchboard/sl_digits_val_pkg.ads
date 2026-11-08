package Sl_Digits_Val_Pkg with SPARK_Mode => On is

   -- Sl_Digits_Val_Pkg computes the decimal value of a run of digits within a string.
   -- It takes a string V, a starting position S, and a count K, returning the numeric value.

   function Digits_Val (V : String; S : Positive; K : Natural) return Natural is
     (if K = 0 then 0 else (Digits_Val (V, S, K - 1) * 10 + (Character'Pos (V (S + (K - 1))) - Character'Pos ('0'))))
   with
     Subprogram_Variant => (Decreases => K),
     Pre =>
       (K <= 9
        and then (if K = 0 then True else ((S >= V'First and then S <= V'Last and then K - 1 <= V'Last - S)))
        and then (for all J in 1 .. K => V (S + (J - 1)) in '0' .. '9')),
     Post =>
       ((Digits_Val'Result =
           (if K = 0 then 0 else (Digits_Val (V, S, K - 1) * 10 + (Character'Pos (V (S + (K - 1))) - Character'Pos ('0'))))
         and then Digits_Val'Result <=
           (case K is
              when 0 => 0,
              when 1 => 9,
              when 2 => 99,
              when 3 => 999,
              when 4 => 9_999,
              when 5 => 99_999,
              when 6 => 999_999,
              when 7 => 9_999_999,
              when 8 => 99_999_999,
              when others => 999_999_999)));

end Sl_Digits_Val_Pkg;
