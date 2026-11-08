with Flat_Number_Pkg;
with Sl_Digits_Val_Pkg;

package Sl_Number_Value_Pkg with SPARK_Mode => On is

   --  This package provides a single function, Number_Value, that converts a
   --  String representation of a decimal integer into its corresponding Integer
   --  value. The input string must be a valid number as defined by the
   --  Flat_Number_Pkg.Number_Ok predicate: either a sequence of one to nine
   --  decimal digits, or an optional leading minus sign followed by one to nine
   --  decimal digits. Leading zeros are permitted and fold away naturally. If
   --  the input string does not satisfy the Number_Ok condition, the function
   --  returns zero. The function is pure and total: it accepts any String
   --  argument, performs no side effects, and always terminates. It relies on
   --  the carried packages Flat_Number_Pkg for validation and Sl_Digits_Val_Pkg
   --  for computing the numeric value of a digit sequence.

   function Number_Value (V : String) return Integer is
     (if not Flat_Number_Pkg.Number_Ok (V) then
        0
      elsif V (V'First) = '-' then
        -(Sl_Digits_Val_Pkg.Digits_Val (V, V'First + 1, V'Length - 1))
      else
        Sl_Digits_Val_Pkg.Digits_Val (V, V'First, V'Length))
     with
       Post =>
         Number_Value'Result =
           (if not Flat_Number_Pkg.Number_Ok (V) then
              0
            elsif V (V'First) = '-' then
              -(Sl_Digits_Val_Pkg.Digits_Val (V, V'First + 1, V'Length - 1))
            else
              Sl_Digits_Val_Pkg.Digits_Val (V, V'First, V'Length))
         and then
           Number_Value'Result in -999_999_999 .. 999_999_999;

end Sl_Number_Value_Pkg;
