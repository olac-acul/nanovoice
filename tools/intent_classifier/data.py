"""Synthetic Italian training data for the local intent classifier. Templates are split into train/test groups so the
test measures generalisation to unseen phrasings, not memorised ones."""
import random, itertools
R = random.Random(7)
DUR = ["cinque minuti", "10 minuti", "un minuto", "mezz'ora", "un'ora", "trenta secondi", "due minuti e mezzo", "venti minuti",
       "un quarto d'ora", "tre minuti", "quaranta secondi", "un'ora e mezza", "otto minuti", "90 secondi", "dodici minuti"]
TIME = ["alle sette", "alle 7:30", "alle sei e mezza", "alle otto meno un quarto", "alle 6", "alle cinque e venti", "alle nove",
        "alle 22", "alle dieci di sera", "alle sette e un quarto", "alle 5:45", "per le sei", "alle undici"]
WHEN = ["", "domani ", "domattina ", "stasera ", "domani mattina "]
PLEASE = ["", " per favore", " grazie", "", ""]
LEAD = ["", "ehi ", "senti ", "scusa ", "", "ok ", "dai "]
def fill(t):
    return (t.replace("{d}", R.choice(DUR)).replace("{t}", R.choice(TIME)).replace("{w}", R.choice(WHEN))
             .replace("{p}", R.choice(PLEASE)).replace("{l}", R.choice(LEAD)))
T = {
 "tell_time": [
   ["{l}che ore sono{p}", "{l}che ora è{p}", "{l}mi dici che ore sono{p}", "{l}dimmi l'ora{p}", "{l}sai dirmi l'ora{p}"],
   ["{l}che ora abbiamo fatto", "{l}che ore si sono fatte", "{l}mi sai dire che ora è{p}", "{l}ora esatta{p}", "{l}quanto è tardi, che ore sono"],
   ["{l}vorrei sapere l'ora{p}", "{l}che ore fai", "{l}a che ora siamo", "{l}dimmi un po' che ore sono", "{l}mi serve sapere l'ora{p}"]],
 "tell_date": [
   ["{l}che giorno è oggi{p}", "{l}che data è oggi{p}", "{l}quanti ne abbiamo oggi", "{l}oggi che giorno è{p}", "{l}in che giorno siamo"],
   ["{l}che giorno della settimana è", "{l}dimmi la data di oggi{p}", "{l}mi dici che giorno è{p}", "{l}la data di oggi qual è", "{l}oggi quanti ne abbiamo"],
   ["{l}vorrei sapere che giorno è{p}", "{l}che data abbiamo oggi", "{l}mi ricordi che giorno è oggi", "{l}siamo a che giorno", "{l}qual è la data{p}"]],
 "set_timer": [
   ["{l}metti un timer di {d}{p}", "{l}imposta un timer di {d}{p}", "{l}timer {d}", "{l}fai partire un timer di {d}{p}", "{l}avvia un timer da {d}"],
   ["{l}fai partire sul telefono un conto alla rovescia di {d}", "{l}avvisami tra {d}{p}", "{l}conto alla rovescia di {d}{p}", "{l}fammi un timer di {d}", "{l}tra {d} fammi suonare il telefono"],
   ["{l}mi metti un countdown di {d}{p}", "{l}fai suonare tra {d}", "{l}cronometrami {d} e poi avvisami", "{l}parti con un timer di {d}", "{l}dammi un avviso fra {d}{p}"]],
 "set_alarm": [
   ["{l}svegliami {w}{t}{p}", "{l}metti la sveglia {w}{t}{p}", "{l}imposta una sveglia {w}{t}{p}", "{l}sveglia {w}{t}", "{l}punta la sveglia {w}{t}"],
   ["{l}{w}svegliami {t}{p}", "{l}mi serve una sveglia {w}{t}", "{l}fammi suonare la sveglia {w}{t}", "{l}programma la sveglia {w}{t}", "{l}voglio svegliarmi {w}{t}"],
   ["{l}domattina devo alzarmi {t}, mettimi la sveglia", "{l}mettimi una sveglia {w}{t}{p}", "{l}crea una sveglia {w}{t}", "{l}sveglia per {w}{t}{p}", "{l}ho bisogno di svegliarmi {w}{t}"]],
 "other": [
   ["{l}che tempo fa domani", "{l}che ore sono a tokyo", "{l}a che ora apre la farmacia", "{l}ricordami di comprare il latte {t}", "{l}quanto dura un timer da cucina",
    "{l}che giorno è nato leonardo da vinci", "{l}quanti giorni mancano a natale", "{l}leggimi le email{p}", "{l}come si cucina la carbonara", "{l}chi ha vinto la partita ieri",
    "{l}scrivi a marco che arrivo tardi", "{l}cerca un ristorante vicino{p}", "{l}traduci ciao in inglese", "{l}quanto fa due per tre", "{l}raccontami una barzelletta"],
   ["{l}a che ora parte il treno per milano", "{l}che ore saranno a new york adesso", "{l}ricordami {w}{t} di chiamare la mamma", "{l}ferma il timer", "{l}cancella la sveglia {t}",
    "{l}quante sveglie ho", "{l}che giorno cade pasqua", "{l}cosa ho in calendario domani", "{l}com'è il traffico verso torino", "{l}trovami il sito di dicio",
    "{l}mandami un messaggio tra {d} con scritto prova", "{l}sai che giorno è il compleanno di paolo", "{l}qual è la capitale del burundi", "{l}spiegami la relatività", "{l}come stai"],
   ["{l}che ora sorge il sole domani", "{l}a che ora chiude il supermercato", "{l}quanto tempo ci vuole per andare a roma", "{l}ricordami tra {d} di spegnere il forno", "{l}la sveglia di stamattina non ha suonato",
    "{l}il timer è finito", "{l}quanto manca al weekend", "{l}che giorno della settimana era il 2 giugno 1946", "{l}consigliami un film", "{l}dove ho parcheggiato",
    "{l}fai una ricerca sulle zanzare", "{l}riassumimi le notizie di oggi", "{l}prenota un tavolo per {t}", "{l}quanto costa un caffè a torino", "{l}che ne pensi di questa giornata"]],
}
def build(groups, n_per_template):
    out = []
    for label, gs in T.items():
        for g in groups:
            for tpl in gs[g]:
                for _ in range(n_per_template):
                    out.append((fill(tpl).strip(), label))
    return out
train = build([0, 1], 25)
test = build([2], 6)
