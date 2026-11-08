package Agent_Text_Pkg with SPARK_Mode is

   --  Purpose: neutralise chat-control markers and line breaks in one agent's text before it is pasted into another agent's prompt.
   --  Usage: the edge first decodes JSON escapes of the source text, then calls Has_Marker and Has_Break to record any attempt,
   --         then calls Make_Plain, and pastes only the Target produced by Make_Plain into the other agent's prompt, never the original text.
   --  This is done for call messages, for noticeboard notes and for store keys and store values alike.
   --  Neutralised openers: the less-than sign followed by the vertical bar; the less-than sign followed by the three bytes with codes 239, 189 and 156;
   --                      the less-than sign followed by one of the four tag texts (think>, /think>, tool_call>, /tool_call>).
   --  The replacement for an opener is the left square bracket.
   --  Neutralised line breaks: LF, VT, FF, CR, NEL, LINE SEPARATOR and PARAGRAPH SEPARATOR.
   --  Every byte of a break becomes the space.

   Max_Text : constant := 4096;

   function Is_Text (T : String) return Boolean is
     (T'First = 1 and then T'Last >= 0 and then T'Last <= Max_Text);

   function Bar_After (T : String; I : Positive) return Boolean is
     (I < T'Last and then T (I + 1) = Character'Val (124))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Wide_Bar_After (T : String; I : Positive) return Boolean is
     (I <= T'Last - 3 and then T (I + 1) = Character'Val (239) and then T (I + 2) = Character'Val (189) and then T (I + 3) = Character'Val (156))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Think_After (T : String; I : Positive) return Boolean is
     (I <= T'Last - 6 and then T (I + 1) = 't' and then T (I + 2) = 'h' and then T (I + 3) = 'i' and then T (I + 4) = 'n' and then T (I + 5) = 'k' and then T (I + 6) = Character'Val (62))
      with Pre => Is_Text (T) and then I <= T'Last;

   function End_Think_After (T : String; I : Positive) return Boolean is
     (I <= T'Last - 7 and then T (I + 1) = Character'Val (47) and then T (I + 2) = 't' and then T (I + 3) = 'h' and then T (I + 4) = 'i' and then T (I + 5) = 'n' and then T (I + 6) = 'k' and then T (I + 7) = Character'Val (62))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Tool_After (T : String; I : Positive) return Boolean is
     (I <= T'Last - 10 and then T (I + 1) = 't' and then T (I + 2) = 'o' and then T (I + 3) = 'o' and then T (I + 4) = 'l' and then T (I + 5) = '_' and then T (I + 6) = 'c' and then T (I + 7) = 'a' and then T (I + 8) = 'l' and then T (I + 9) = 'l' and then T (I + 10) = Character'Val (62))
      with Pre => Is_Text (T) and then I <= T'Last;

   function End_Tool_After (T : String; I : Positive) return Boolean is
     (I <= T'Last - 11 and then T (I + 1) = Character'Val (47) and then T (I + 2) = 't' and then T (I + 3) = 'o' and then T (I + 4) = 'o' and then T (I + 5) = 'l' and then T (I + 6) = '_' and then T (I + 7) = 'c' and then T (I + 8) = 'a' and then T (I + 9) = 'l' and then T (I + 10) = 'l' and then T (I + 11) = Character'Val (62))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Tag_After (T : String; I : Positive) return Boolean is
     (Think_After (T, I) or else End_Think_After (T, I) or else Tool_After (T, I) or else End_Tool_After (T, I))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Opens_Marker (T : String; I : Positive) return Boolean is
     (T (I) = Character'Val (60) and then (Bar_After (T, I) or else Wide_Bar_After (T, I) or else Tag_After (T, I)))
      with Pre => Is_Text (T) and then I <= T'Last;

   function One_Byte_Break (T : String; I : Positive) return Boolean is
     (T (I) = Character'Val (10) or else T (I) = Character'Val (11) or else T (I) = Character'Val (12) or else T (I) = Character'Val (13))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Nel_At (T : String; I : Positive) return Boolean is
     (I <= T'Last - 1 and then T (I) = Character'Val (194) and then T (I + 1) = Character'Val (133))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Sep_At (T : String; I : Positive) return Boolean is
     (I <= T'Last - 2 and then T (I) = Character'Val (226) and then T (I + 1) = Character'Val (128) and then (T (I + 2) = Character'Val (168) or else T (I + 2) = Character'Val (169)))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Break_Starts (T : String; I : Positive) return Boolean is
     (One_Byte_Break (T, I) or else Nel_At (T, I) or else Sep_At (T, I))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Nel_Tail (T : String; I : Positive) return Boolean is
     (I > 1 and then Nel_At (T, I - 1))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Sep_Mid (T : String; I : Positive) return Boolean is
     (I > 1 and then Sep_At (T, I - 1))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Sep_Tail (T : String; I : Positive) return Boolean is
     (I > 2 and then Sep_At (T, I - 2))
      with Pre => Is_Text (T) and then I <= T'Last;

   function In_Break (T : String; I : Positive) return Boolean is
     (Break_Starts (T, I) or else Nel_Tail (T, I) or else Sep_Mid (T, I) or else Sep_Tail (T, I))
      with Pre => Is_Text (T) and then I <= T'Last;

   function Has_Marker (T : String) return Boolean is
     (for some I in T'Range => Opens_Marker (T, I))
      with Pre => Is_Text (T);

   function Has_Break (T : String) return Boolean is
     (for some I in T'Range => Break_Starts (T, I))
      with Pre => Is_Text (T);

   function Plain_Char (T : String; I : Positive) return Character is
     ((if Opens_Marker (T, I) then Character'Val (91) else (if In_Break (T, I) then Character'Val (32) else T (I))))
      with Pre => Is_Text (T) and then I <= T'Last,
           Post => (if Opens_Marker (T, I) then Plain_Char'Result = Character'Val (91)) and then
                   (if not Opens_Marker (T, I) and then In_Break (T, I) then Plain_Char'Result = Character'Val (32)) and then
                   (if not Opens_Marker (T, I) and then not In_Break (T, I) then Plain_Char'Result = T (I));

   procedure Make_Plain (Source : String; Target : out String)
      with Pre => Is_Text (Source) and then Target'First = 1 and then Target'Last = Source'Last,
           Post => (for all I in Source'Range => Target (I) = Plain_Char (Source, I)) and then
                   Is_Text (Target) and then not Has_Marker (Target) and then
                   not Has_Break (Target) and then
                   (if not Has_Marker (Source) and then not Has_Break (Source) then (for all I in Source'Range => Target (I) = Source (I))) and then
                   Target'Last = Source'Last;

end Agent_Text_Pkg;
